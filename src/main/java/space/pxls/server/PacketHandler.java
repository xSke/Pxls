package space.pxls.server;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.async.Callback;
import com.mashape.unirest.http.exceptions.UnirestException;
import com.typesafe.config.Config;
import io.undertow.websockets.core.WebSocketChannel;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.json.JSONObject;

import space.pxls.App;
import space.pxls.data.DBChatMessage;
import space.pxls.data.DBPixelPlacementFull;
import space.pxls.server.packets.chat.*;
import space.pxls.server.packets.socket.*;
import space.pxls.user.Faction;
import space.pxls.user.User;
import space.pxls.util.TextFilter;
import space.pxls.util.RateLimitFactory;

import java.io.*;
import java.net.*;

import java.time.Instant;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringEscapeUtils.escapeHtml4;

public class PacketHandler {
    private UndertowServer server;
    private int numAllCons = 0;

    public int getCooldown() {
        Config config = App.getConfig();

        String cooldownType = config.getString("cooldownType").toLowerCase();
        if (cooldownType.equalsIgnoreCase("activity")) {
            double x = server.getNonIdledUsersCount();
            double s = config.getDouble("activityCooldown.steepness");
            double u = config.getDouble("activityCooldown.userOffset");
            double t = config.getDouble("activityCooldown.globalOffset");

            // Formula by Atomic10 and c4rt
            // https://www.desmos.com/calculator/sgphb1abzi
            double cooldown = s * Math.sqrt(x + u) + t;

            double multiplier = config.getDouble("activityCooldown.multiplier");
            cooldown *= multiplier;

            return (int) Math.abs(cooldown);
        } else {
            return (int) config.getDuration("staticCooldown.time", TimeUnit.SECONDS);
        }
    }

    public PacketHandler(UndertowServer server) {
        this.server = server;
    }

    public void userdata(WebSocketChannel channel, User user) {
        if (user != null) {
            server.send(channel, new ServerUserInfo(
                    user.getName(),
                    user.getAllRoles(),
                    user.getPixelCount(),
                    user.getAllTimePixelCount(),
                    user.isBanned(),
                    user.getBanExpiryTime(),
                    user.getBanReason(),
                    user.loginsWithIP() ? "ip" : "service",
                    user.getPlaceOverrides(),
                    user.isChatbanned(),
                    App.getDatabase().getChatBanReason(user.getId()),
                    user.isPermaChatbanned(),
                    user.getChatbanExpiryTime(),
                    user.isRenameRequested(true),
                    user.getDiscordName(),
                    user.getChatNameColor()
            ));
            sendAvailablePixels(channel, user, "auth");
        }
    }

    public void connect(WebSocketChannel channel, User user) {
        if (user != null) {
            userdata(channel, user);
            sendCooldownData(channel, user);
            user.flagForCaptcha();
            server.addAuthedUser(user);

            user.setInitialAuthTime(System.currentTimeMillis());
            user.tickStack(false); // pop the whole pixel stack
            sendAvailablePixels(channel, user, "connect");
        }
        numAllCons++;

        updateUserData();
    }

    public void disconnect(WebSocketChannel channel, User user) {
        if (user != null && user.getConnections().size() == 0) {
            server.removeAuthedUser(user);
        }
        numAllCons--;

        updateUserData();
    }

    public void accept(WebSocketChannel channel, User user, Object obj, String ip) {
        if (user == null) return;
        if (obj instanceof ClientPlace && user.hasPermission("board.place")) handlePlace(channel, user, ((ClientPlace) obj), ip);
        if (obj instanceof ClientUndo && user.hasPermission("board.undo")) handleUndo(channel, user, ((ClientUndo) obj), ip);
        if (obj instanceof ClientCaptcha) handleCaptcha(channel, user, ((ClientCaptcha) obj));
        if (obj instanceof ClientShadowBanMe) handleShadowBanMe(channel, user, ((ClientShadowBanMe) obj));
        if (obj instanceof ClientBanMe) handleBanMe(channel, user, ((ClientBanMe) obj));
        if (App.isChatEnabled()) {
            if (obj instanceof ClientChatHistory && user.hasPermission("chat.history") && !user.isBanned()) handleChatHistory(channel, user, ((ClientChatHistory) obj));
            if (obj instanceof ClientChatbanState) handleChatbanState(channel, user, ((ClientChatbanState) obj));
            if (obj instanceof ClientChatMessage && user.hasPermission("chat.send")) handleChatMessage(channel, user, ((ClientChatMessage) obj));
            if (obj instanceof ClientChatLookup && user.hasPermission("chat.lookup")) handleChatLookup(channel, user, ((ClientChatLookup) obj));
        }
        if (obj instanceof ClientAdminPlacementOverrides && user.hasPermission("user.admin")) handlePlacementOverrides(channel, user, ((ClientAdminPlacementOverrides) obj));
        if (obj instanceof ClientAdminMessage && user.hasPermission("user.alert")) handleAdminMessage(channel, user, ((ClientAdminMessage) obj));
    }

    private void handleAdminMessage(WebSocketChannel channel, User user, ClientAdminMessage obj) {
        User u = App.getUserManager().getByName(obj.getUsername());
        if (u != null) {
            ServerAlert msg = new ServerAlert(user.getName(), escapeHtml4(obj.getMessage()));
            App.getDatabase().insertAdminLog(user.getId(), String.format("Sent an alert to %s (UID: %d) with the content: %s", u.getName(), u.getId(), escapeHtml4(obj.getMessage())));
            for (WebSocketChannel ch : u.getConnections()) {
                server.send(ch, msg);
            }
        }
    }

    private void handleChatLookup(WebSocketChannel channel, User user, ClientChatLookup obj) {
        ServerChatLookup scl;
        String username = obj.getArg();
        if (obj.getMode().equalsIgnoreCase("cmid")) {
            Integer i = null;
            try {
                i = Integer.parseInt(obj.getArg());
            } catch (NumberFormatException nfe) {
                server.send(channel, new ServerError("Invalid message ID supplied"));
            }
            if (i != null) {
                DBChatMessage chatMessage = App.getDatabase().getChatMessageByID(i);
                if (chatMessage != null) {
                    User fromChatMessage = App.getUserManager().getByID(chatMessage.author_uid);
                    username = fromChatMessage != null ? fromChatMessage.getName() : null;
                }
            }
        }
        scl = username != null ? App.getDatabase().runChatLookupForUsername(username, App.getConfig().getInt("chat.chatLookupScrollbackAmount")) : null;
        server.send(channel, scl == null ? new ServerError("User doesn't exist") : scl);
    }

    private void handleShadowBanMe(WebSocketChannel channel, User user, ClientShadowBanMe obj) {
        if (!user.isBanned() && !user.isShadowBanned()) {
            App.getDatabase().insertAdminLog(user.getId(), String.format("shadowban %s with reason: self-shadowban via script; %s", user.getName(), obj.getReason()));
            user.shadowBan(String.format("auto-ban via script; %s", obj.getReason()), 999*24*3600, user);
        }
    }

    private void handleBanMe(WebSocketChannel channel, User user, ClientBanMe obj) {
        String app = obj.getReason();
        App.getDatabase().insertAdminLog(user.getId(), String.format("permaban %s with reason: auto-ban via script; %s", user.getName(), app));
        user.ban(0, String.format("auto-ban via script; %s", app), 0, user);
    }

    private void handlePlacementOverrides(WebSocketChannel channel, User user, ClientAdminPlacementOverrides obj) {
        if (obj.hasIgnoreCooldown() != null) {
            user.maybeSetIgnoreCooldown(obj.hasIgnoreCooldown());
        }
        if (obj.getCanPlaceAnyColor() != null) {
            user.maybeSetCanPlaceAnyColor(obj.getCanPlaceAnyColor());
        }
        if (obj.hasIgnorePlacemap() != null) {
            user.maybeSetIgnorePlacemap(obj.hasIgnorePlacemap());
        }

        for (WebSocketChannel ch : user.getConnections()) {
            sendPlacementOverrides(ch, user);
            sendCooldownData(ch, user);
        }
    }

    private void handleUndo(WebSocketChannel channel, User user, ClientUndo cu, String ip){
        boolean _canUndo = user.canUndo(true);
        if (!_canUndo || user.undoWindowPassed()) {
            return;
        }
        if (user.isShadowBanned()) {
            user.setCooldown(0);
            sendCooldownData(user);
            return;
        }
        boolean gotLock = user.tryGetUndoLock();
        if (gotLock) {
            try {
                DBPixelPlacementFull thisPixel = App.getDatabase().getUserUndoPixel(user);
                Optional<DBPixelPlacementFull> recentPixel = App.getDatabase().getFullPixelAt(thisPixel.x, thisPixel.y);
                if (!recentPixel.isPresent()) return;
                if (thisPixel.id != recentPixel.get().id) return;

                if (user.lastPlaceWasStack()) {
                    user.setStacked(Math.min(user.getStacked() + 1, App.getConfig().getInt("stacking.maxStacked")));
                    sendAvailablePixels(user, "undo");
                }
                user.setCooldown(0);
                DBPixelPlacementFull lastPixel = App.getDatabase().getPixelByID(null, thisPixel.secondaryId);
                if (lastPixel != null) {
                    App.getDatabase().putUserUndoPixel(lastPixel, user, thisPixel.id);
                    App.putPixel(lastPixel.x, lastPixel.y, lastPixel.color, user, false, ip, false, "user undo");
                    user.decreasePixelCounts();
                    broadcastPixelUpdate(lastPixel.x, lastPixel.y, lastPixel.color);
                    ackUndo(user, lastPixel.x, lastPixel.y);
                } else {
                    byte defaultColor = App.getDefaultColor(thisPixel.x, thisPixel.y);
                    App.getDatabase().putUserUndoPixel(thisPixel.x, thisPixel.y, defaultColor, user, thisPixel.id);
                    user.decreasePixelCounts();
                    App.putPixel(thisPixel.x, thisPixel.y, defaultColor, user, false, ip, false, "user undo");
                    broadcastPixelUpdate(thisPixel.x, thisPixel.y, defaultColor);
                    ackUndo(user, thisPixel.x, thisPixel.y);
                }
                sendAvailablePixels(user, "undo");
                sendCooldownData(user);
                sendPixelCountUpdate(user);
            } finally {
                user.releaseUndoLock();
            }
        }
    }

    private void handlePlace(WebSocketChannel channel, User user, ClientPlace cp, String ip) {
        if (!cp.getType().equals("pixel")) {
            handlePlaceMaybe(channel, user, cp, ip);
        }
        if (cp.getX() < 0 || cp.getX() >= App.getWidth() || cp.getY() < 0 || cp.getY() >= App.getHeight()) return;
        if (user.isBanned()) return;
        if (!user.canPlaceColor(cp.getColor())) return;

        if (user.canPlace()) {
            boolean gotLock = user.tryGetPlacingLock();
            if (gotLock) {
                try {
                    boolean doCaptcha = (user.isOverridingCaptcha() || App.isCaptchaEnabled()) && App.isCaptchaConfigured();
                    if (doCaptcha) {
                        int pixels = App.getConfig().getInt("captcha.maxPixels");
                        if (!user.isOverridingCaptcha() && pixels != 0) {
                            boolean allTime = App.getConfig().getBoolean("captcha.allTime");
                            doCaptcha = (allTime ? user.getAllTimePixelCount() : user.getPixelCount()) < pixels;
                        }
                    }
                    if (user.updateCaptchaFlagPrePlace() && doCaptcha) {
                        server.send(channel, new ServerCaptchaRequired());
                    } else {
                        int c = App.getPixel(cp.getX(), cp.getY());
                        boolean isInsidePlacemap = false;
                        if (App.getHavePlacemap()) {
                            int placemapType = App.getPlacemap(cp.getX(), cp.getY());
                            switch (placemapType) {
                                case 0:
                                    // Allow normal placement
                                    isInsidePlacemap = c != cp.getColor();
                                    break;
                                case 2:
                                    // Allow tendril placement
                                    int top = App.getPixel(cp.getX(), cp.getY() + 1);
                                    int left = App.getPixel(cp.getX() - 1, cp.getY());
                                    int right = App.getPixel(cp.getX() + 1, cp.getY());
                                    int bottom = App.getPixel(cp.getX(), cp.getY() - 1);

                                    int defaultTop = App.getDefaultColor(cp.getX(), cp.getY() + 1);
                                    int defaultLeft = App.getDefaultColor(cp.getX() - 1, cp.getY());
                                    int defaultRight = App.getDefaultColor(cp.getX() + 1, cp.getY());
                                    int defaultBottom = App.getDefaultColor(cp.getX(), cp.getY() - 1);
                                    if (top != defaultTop || left != defaultLeft || right != defaultRight || bottom != defaultBottom) {
                                        // The pixel has at least one other attached pixel
                                        isInsidePlacemap = c != cp.getColor() && c != 0xFF && c != -1;
                                    }
                                    break;
                            }
                        } else {
                            isInsidePlacemap = c != cp.getColor() && c != 0xFF && c != -1;
                        }
                        int c_old = c;
                        if (user.hasIgnorePlacemap() || isInsidePlacemap) {
                            int seconds = getCooldown();
                            if (c_old != 0xFF && c_old != -1 && App.getDatabase().shouldPixelTimeIncrease(user.getId(), cp.getX(), cp.getY()) && App.getConfig().getBoolean("backgroundPixel.enabled")) {
                                seconds = (int)Math.round(seconds * App.getConfig().getDouble("backgroundPixel.multiplier"));
                            }
                            if (user.isShadowBanned()) {
                                // ok let's just pretend to set a pixel...
                                App.logShadowbannedPixel(cp.getX(), cp.getY(), cp.getColor(), user.getName(), ip);
                                ServerPlace msg = new ServerPlace(Collections.singleton(new ServerPlace.Pixel(cp.getX(), cp.getY(), cp.getColor())));
                                for (WebSocketChannel ch : user.getConnections()) {
                                    server.send(ch, msg);
                                }
                                ackPlace(user, cp.getX(), cp.getY());
                                if (user.canUndo(false)) {
                                    server.send(channel, new ServerCanUndo(App.getConfig().getDuration("undo.window", TimeUnit.SECONDS)));
                                }
                            } else {
                                boolean modAction = cp.getColor() == 0xFF || user.hasIgnoreCooldown() || (user.hasIgnorePlacemap() && !isInsidePlacemap);
                                App.putPixel(cp.getX(), cp.getY(), cp.getColor(), user, modAction, ip, true, "");
                                App.saveMap();
                                broadcastPixelUpdate(cp.getX(), cp.getY(), cp.getColor());
                                ackPlace(user, cp.getX(), cp.getY());
                                sendPixelCountUpdate(user);
                            }
                            if (!user.hasIgnoreCooldown()) {
                                if (user.isIdled()) {
                                    user.setIdled(false);
                                    updateUserData();
                                }
                                user.setLastPixelTime();
                                if (user.getStacked() > 0) {
                                    user.setLastPlaceWasStack(true);
                                    user.setStacked(user.getStacked()-1);
                                    sendAvailablePixels(user, "consume");
                                } else {
                                    user.setLastPlaceWasStack(false);
                                    user.setCooldown(seconds);
                                    App.getDatabase().updateUserTime(user.getId(), seconds);
                                    sendAvailablePixels(user, "consume");
                                }

                                if (user.canUndo(false)) {
                                    server.send(channel, new ServerCanUndo(App.getConfig().getDuration("undo.window", TimeUnit.SECONDS)));
                                }
                            }

                            sendCooldownData(user);
                        }
                    }
                } finally {
                    user.releasePlacingLock();
                }
            }
        }
    }

    private void handlePlaceMaybe(WebSocketChannel channel, User user, ClientPlace cp, String ip) {
    }

    private void handleCaptcha(WebSocketChannel channel, User user, ClientCaptcha cc) {
        if (!user.isFlaggedForCaptcha()) return;
        if (user.isBanned()) return;

        Unirest
                .post("https://www.google.com/recaptcha/api/siteverify")
                .field("secret", App.getConfig().getString("captcha.secret"))
                .field("response", cc.getToken())
                //.field("remoteip", "null")
                .asJsonAsync(new Callback<JsonNode>() {
                    @Override
                    public void completed(HttpResponse<JsonNode> response) {
                        JsonNode body = response.getBody();

                        String hostname = App.getConfig().getString("host");

                        boolean success = body.getObject().getBoolean("success") && body.getObject().getString("hostname").equals(hostname);
                        if (success) {
                            user.validateCaptcha();
                        }

                        server.send(channel, new ServerCaptchaStatus(success));
                    }

                    @Override
                    public void failed(UnirestException e) {

                    }

                    @Override
                    public void cancelled() {

                    }
                });
    }

    public void handleChatbanState(WebSocketChannel channel, User user, ClientChatbanState clientChatbanState) {
        server.send(channel, new ServerChatbanState(user.isPermaChatbanned(), user.getChatbanReason(), user.getChatbanExpiryTime()));
    }

    public void handleChatHistory(WebSocketChannel channel, User user, ClientChatHistory clientChatHistory) {
        boolean includePurged = user.hasPermission("chat.history.purged");
        var messages = App.getDatabase().getLastXMessages(100, includePurged).stream()
                .map(dbChatMessage -> {
                    List<Badge> badges = new ArrayList<>();
                    String authorName = "CONSOLE";
                    int nameColor = 0;
                    Faction faction = null;
                    List<String> nameClass = null;
                    if (dbChatMessage.author_uid > 0) {
                        authorName = "$Unknown";
                        User author = App.getUserManager().getByID(dbChatMessage.author_uid);
                        if (author != null) {
                            authorName = author.getName();
                            badges = author.getChatBadges();
                            nameColor = author.getChatNameColor();
                            nameClass = author.getChatNameClasses();
                            faction = author.fetchDisplayedFaction();
                        }
                    }
                    var message = new ChatMessage(
                        dbChatMessage.id,
                        authorName,
                        dbChatMessage.sent,
                        App.getConfig().getBoolean("textFilter.enabled") && dbChatMessage.filtered_content.length() > 0
                            ? dbChatMessage.filtered_content
                            : dbChatMessage.content,
                        dbChatMessage.purged
                            ? new ChatMessage.Purge(dbChatMessage.purged_by_uid, dbChatMessage.purge_reason)
                            : null,
                        badges,
                        nameClass,
                        nameColor,
                        dbChatMessage.author_was_shadow_banned,
                        faction
                    );
                    if (user.isShadowBanned() && dbChatMessage.author_uid == user.getId()) {
                        message = message.asShadowBanned();
                    }
                    if (!includePurged && App.getSnipMode()) {
                        message = message.asSnipRedacted();
                    }
                    return message;
                })
                .filter(message -> !message.getAuthorWasShadowBanned() || user.hasPermission("chat.history.shadowbanned"))
                .collect(Collectors.toList());
        server.send(channel, new ServerChatHistory(messages));
    }

    public void handleChatMessage(WebSocketChannel channel, User user, ClientChatMessage clientChatMessage) {
        int charLimit = Math.min(App.getConfig().getInt("chat.characterLimit"), 2048);
        if (charLimit <= 0) {
            charLimit = 2048;
        }
        Long nowMS = System.currentTimeMillis();
        String message = clientChatMessage.getMessage();
        if (message.contains("\r")) message = message.replaceAll("\r", "");
        if (message.endsWith("\n")) message = message.replaceFirst("\n$", "");
        if (message.length() > charLimit) message = message.substring(0, charLimit);
        if (user == null) { //console
            Integer cmid = App.getDatabase().createChatMessage(0, nowMS / 1000L, message, "", false);
            server.broadcast(new ServerChatMessage(new ChatMessage(cmid, "CONSOLE", nowMS / 1000L, message, null, null, null, 0, false, null)));
        } else {
            if (!user.canChat()) return;
            if (message.trim().length() == 0) return;
            if (user.isRenameRequested(false)) return;
            int remaining = RateLimitFactory.getTimeRemaining(DBChatMessage.class, String.valueOf(user.getId()));
            if (remaining > 0) {
                server.send(user, new ServerChatCooldown(remaining, message));
                return;
            }
            try {
                String toSend = message;
                if (App.getConfig().getBoolean("chat.trimInput"))
                    toSend = toSend.trim();
                Faction usersFaction = user.fetchDisplayedFaction();
                String toFilter = "";
                if (App.getConfig().getBoolean("textFilter.enabled")) {
                    TextFilter.FilterResult result = TextFilter.getInstance().filter(toSend);
                    toSend = result.filterHit ? result.filtered : result.original;
                    toFilter = toSend;
                }
                Integer cmid = App.getDatabase().createChatMessage(user.getId(), nowMS / 1000L, message, toFilter, user.isShadowBanned());
                var chatMessage = new ChatMessage(cmid, user.getName(), nowMS / 1000L, toSend, null, user.getChatBadges(), user.getChatNameClasses(), user.getChatNameColor(), user.isShadowBanned(), usersFaction);

                var barePacket = new ServerChatMessage(chatMessage);
                var userPacket = App.getSnipMode() ? barePacket.asSnipRedacted() : barePacket;
                var staffPacket = barePacket;
                if (user.isShadowBanned()) {
                    // To the user, it looks like their message was sent successfully.
                    server.send(user, userPacket.asShadowBanned());
                    // To other users, nothing was sent.
                    userPacket = null;
                    // To staff, if enabled in the config, they will be the only ones to also get the message.
                    staffPacket = App.getConfig().getBoolean("chat.showShadowBannedMessagesToStaff") ? staffPacket : null;
                }
                if (userPacket != null || staffPacket != null) {
                    server.broadcastSeparateForStaff(userPacket, staffPacket);
                    if(userPacket != null) {
                        relayChatMessageToWebhooks(userPacket.getMessage(), App.getConfig().getStringList("chat.publicWebhooks"));
                    }

                    if(staffPacket != null) {
                        relayChatMessageToWebhooks(staffPacket.getMessage(), App.getConfig().getStringList("chat.staffWebhooks"));
                    }
                }
            } catch (UnableToExecuteStatementException utese) {
                utese.printStackTrace();
                System.err.println("Failed to execute the ChatMessage insert statement.");
            }
        }
    }

    private void relayChatMessageToWebhooks(ChatMessage message, List<String> webhooks) {
        // NOTE ([  ]): these are very much discord embeds at the moment.
        // see https://discord.com/developers/docs/resources/channel#embed-object
        var embed = new JSONObject();

        embed.put("description", message.getMessage_raw());
        embed.put("timestamp", Instant.ofEpochSecond(message.getDate()).toString());
        embed.put("color", Long.decode("0x" + App.getPalette().getColors().get(message.getAuthorNameColor().intValue()).getValue()));

        var author = new JSONObject();
        // NOTE ([  ]): There's no clean way to determining if we're on http or https
        // so I gave up — you should be using https anyway.
        try {
            var authorProfile = new URL("https://" + App.getConfig().getString("host") + "/profile/" + message.getAuthor() + "/");

            author.put("name", message.getAuthor());
            author.put("url", authorProfile);

            embed.put("author", author);
        } catch(MalformedURLException e) {
            e.printStackTrace();
        }

        var postDataBuilder = new JSONObject();

        postDataBuilder.put("embeds", new JSONObject[] { embed });

        var postData = postDataBuilder.toString();
        System.out.println(postData);

        for(var hook : webhooks) {
            try {
                var connection = (HttpURLConnection) new URL(hook).openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                System.out.println(connection.getRequestProperties().toString());
                var postDataStream = new DataOutputStream(connection.getOutputStream());
                postDataStream.writeBytes(postData);
                postDataStream.flush();
                postDataStream.close();

                if(connection.getResponseCode() == 200) {
                    var response = new BufferedReader(new InputStreamReader(connection.getInputStream()));

                    for(var line: response.lines().collect(Collectors.toList())) {
                        System.out.println(line);
                    }

                    response.close();
                }

                // NOTE ([  ]): this error code might be a bit cryptic when printed,
                // but I don't want to clean it up and it's better than failing silently.
                if(connection.getResponseCode() >= 400) {
                    var response = new BufferedReader(new InputStreamReader(connection.getErrorStream()));

                    for(var line: response.lines().collect(Collectors.toList())) {
                        System.err.println(line);
                    }

                    response.close();
                }
            } catch(Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void sendChatban(User user, ServerChatBan chatban) {
        server.send(user, chatban);
    }

    public void sendChatPurge(User target, User initiator, int amount, String reason) {
        var barePacket = new ServerChatPurge(target.getName(), initiator == null ? "CONSOLE" : initiator.getName(), amount, reason);
        var redactedPacket = App.getSnipMode() ? barePacket.asSnipRedacted() : barePacket;
        server.broadcastSeparateForStaff(redactedPacket, barePacket);
    }

    public void sendSpecificPurge(User target, User initiator, Integer cmid, String reason) {
        sendSpecificPurge(target, initiator, Collections.singletonList(cmid), reason);
    }

    public void sendSpecificPurge(User target, User initiator, List<Integer> cmids, String reason) {
        var barePacket = new ServerChatSpecificPurge(target.getName(), initiator == null ? "CONSOLE" : initiator.getName(), cmids, reason);
        var redactedPacket = App.getSnipMode() ? barePacket.asSnipRedacted() : barePacket;
        server.broadcastSeparateForStaff(redactedPacket, barePacket);
    }

    public void updateUserData() {
        server.broadcast(new ServerUsers(App.getServer().getNonIdledUsersCount()));
    }

    private void sendPlacementOverrides(WebSocketChannel channel, User user) {
        server.send(channel, new ServerAdminPlacementOverrides(user.getPlaceOverrides()));
    }

    public void sendPlacementOverrides(User user) {
        for (WebSocketChannel ch : user.getConnections()) {
            sendPlacementOverrides(ch, user);
        }
    }

    private void sendCooldownData(WebSocketChannel channel, User user) {
        server.send(channel, new ServerCooldown(user.getRemainingCooldown()));
    }

    private void sendCooldownData(User user) {
        for (WebSocketChannel ch : user.getConnections()) {
            sendCooldownData(ch, user);
        }
    }

    private void broadcastPixelUpdate(int x, int y, int color) {
        server.broadcast(new ServerPlace(Collections.singleton(new ServerPlace.Pixel(x, y, color))));
    }

    public void sendAvailablePixels(WebSocketChannel ch, User user, String cause) {
        server.send(ch, new ServerPixels(user.getAvailablePixels(), cause));
    }
    public void sendAvailablePixels(User user, String cause) {
        for (WebSocketChannel ch : user.getConnections()) {
            sendAvailablePixels(ch, user, cause);
        }
    }

    public void sendPixelCountUpdate(User user) {
        for (WebSocketChannel ch : user.getConnections()) {
            server.send(ch, new ServerPixelCountUpdate(user));
        }
    }

    private void ackUndo(User user, int x, int y) {
        ack(user, "UNDO", x, y);
    }

    private void ackPlace(User user, int x, int y) {
        ack(user, "PLACE", x, y);
    }

    private void ack(User user, String _for, int x, int y) {
        for (WebSocketChannel ch : user.getConnections()) {
            server.send(ch, new ServerACK(_for, x, y));
        }
    }

    public int getNumAllCons() {
        return numAllCons;
    }
}
