package space.pxls.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mashape.unirest.http.exceptions.UnirestException;
import com.mitchellbosecke.pebble.PebbleEngine;
import com.mitchellbosecke.pebble.loader.ClasspathLoader;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.CookieImpl;
import io.undertow.server.handlers.CookieSameSiteMode;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.util.*;
import space.pxls.App;
import space.pxls.auth.*;
import space.pxls.data.*;
import space.pxls.server.packets.http.Error;
import space.pxls.server.packets.http.*;
import space.pxls.server.packets.socket.*;
import space.pxls.user.*;
import space.pxls.util.*;

import java.io.*;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class WebHandler {
    private PebbleEngine engine;
    private Map<String, AuthService> services = new ConcurrentHashMap<>();
    public static final String TEMPLATE_PROFILE = "public/pebble_templates/profile.html";
    public static final String TEMPLATE_40X = "public/pebble_templates/40x.html";
    public static final String TEMPLATE_INDEX = "public/pebble_templates/index.html";

    public WebHandler() {
        addServiceIfAvailable("reddit", new RedditAuthService("reddit"));
        addServiceIfAvailable("google", new GoogleAuthService("google"));
        addServiceIfAvailable("discord", new DiscordAuthService("discord"));
        addServiceIfAvailable("vk", new VKAuthService("vk"));
        addServiceIfAvailable("tumblr", new TumblrAuthService("tumblr"));

        engine = new PebbleEngine.Builder().build();
    }

    private String fileToString(File f) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            String s = "";
            String line;
            while ((line = br.readLine()) != null) {
                s += line + "\n";
            }
            br.close();
            return s;
        } catch (IOException e) {
            return "";
        }
    }

    private String fileToString(String s) {
        return fileToString(new File(s));
    }

    private String resourceToString(String r) {
        try {
            InputStream in = getClass().getResourceAsStream(r);
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            String s = "";
            String line;
            while ((line = br.readLine()) != null) {
                s += line + "\n";
            }
            return s;
        } catch (IOException e) {
            return "";
        }
    }

    public void index(HttpServerExchange exchange) {
        File index_cache = new File(App.getStorageDir().resolve("index_cache.html").toString());
        if (index_cache.exists()) {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html");
            exchange.getResponseSender().send(fileToString(index_cache));
            return;
        }
        Map<String,Object> replacements = new HashMap<>();
        String[] keys = {"title", "head", "info", "faq"};
        for (String p : keys) {
            String r = App.getConfig().getString("html." + p);
            if (r == null) {
                r = "";
            }
            replacements.put(p, r);
        }
        try {
            FileWriter fw = new FileWriter(index_cache);
            engine.getTemplate(TEMPLATE_INDEX).evaluate(fw, replacements);
            fw.flush();
            fw.close();
            index(exchange); // we created the file, now output it!
        } catch (IOException e) {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html");
            exchange.getResponseSender().send("error");
            return;
        }
    }

    public void view40x(HttpServerExchange exchange, int x, User user) {
        if (x < 100) x = x + 400;
        Map<String,Object> m = new HashMap<>();
        m.put("err", x);
        if (user != null) {
            m.put("requesting_user", x);
        }
        String toRet = String.format("<p style=\"text-align: center;\">Socc broke the %d page! Let someone know please :) <a href=\"/\">Back to Root</a></p>", x);
        try {
            Writer writer = new StringWriter();
            engine.getTemplate(TEMPLATE_40X).evaluate(writer, m);
            toRet = writer.toString();
        } catch (Exception e) {
            e.printStackTrace();
        }
        exchange.setStatusCode(x);
        exchange.getResponseSender().send(toRet);
    }

    public void profileView(HttpServerExchange exchange) {
        final User user = exchange.getAttachment(AuthReader.USER);
        if (user == null) {
            view40x(exchange, 403, null);
        } else {
            PathTemplateMatch match = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY);
            String requested = match.getParameters().compute("who", (k, s) -> (s == null || s.isEmpty()) ? user.getName() : s);
            boolean requested_self = user.getName().equals(requested); // usernames are case-sensitive, we can use #equals relatively safely.
            User profileUser = requested_self ? user : App.getUserManager().getByName(requested);

            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html");

            HashMap<String,Object> m = new HashMap<>();
            m.put("requesting_user", user);

            if (profileUser == null) {
                view40x(exchange, 404, user);
            } else {
                String toRet = "<p style=\"text-align: center;\">Socc probably broke something... let someone know please.</p>";
                try {
                    List<DBChatReport> chatReports = new ArrayList<>();
                    List<DBCanvasReport> canvasReports = new ArrayList<>();
                    List<Faction> factions = App.getDatabase().getFactionsForUID(profileUser.getId()).stream().map(Faction::new).collect(Collectors.toList());

                    m.put("snip_mode", App.getSnipMode());
                    m.put("requested_self", requested_self);
                    m.put("profile_of", profileUser);
                    m.put("factions", factions);
                    m.put("palette", App.getPalette().getColors().stream().map(color -> color.getValue()).collect(Collectors.toList()));
                    m.put("route_root", requested_self ? "/profile" : String.format("/profile/%s", requested));

                    if (requested_self) {
                        try {
                            chatReports = App.getDatabase().getChatReportsFromUser(profileUser.getId());
                            canvasReports = App.getDatabase().getCanvasReportsFromUser(profileUser.getId());
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        m.put("requesting_user_canvas_pixels", user.getPixelCount());
                        m.put("requesting_user_alltime_pixels", user.getAllTimePixelCount());
                        m.put("new_fac_min_pixels", App.getConfig().getInt("factions.minPixelsToCreate"));
                        m.put("max_faction_tag_length", App.getConfig().getInt("factions.maxTagLength"));
                        m.put("max_faction_name_length", App.getConfig().getInt("factions.maxNameLength"));
                        m.put("chat_reports", chatReports);
                        m.put("chat_reports_open_count", chatReports.stream().filter(dbChatReport -> !dbChatReport.closed).count());
                        m.put("canvas_reports", canvasReports);
                        m.put("canvas_reports_open_count", canvasReports.stream().filter(dbCanvasReport -> !dbCanvasReport.closed).count());
                    }

                    Writer writer = new StringWriter();
                    engine.getTemplate(TEMPLATE_PROFILE).evaluate(writer, m);
                    toRet = writer.toString();
                } catch (Exception e) {
                    e.printStackTrace();
                }

                exchange.setStatusCode(200);
                exchange.getResponseSender().send(toRet);
            }

        }
    }

    public void getRequestingUserFactions(HttpServerExchange exchange) throws Exception {
        User user = exchange.getAttachment(AuthReader.USER);
        if (user == null) {
            send(StatusCodes.UNAUTHORIZED, exchange, "Not Authorized");
        } else {
            exchange.setStatusCode(StatusCodes.OK);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseSender().send(App.getGson().toJson(App.getDatabase().getFactionsForUID(user.getId()).stream().map(dbf -> dbf.owner == user.getId() ? new ExtendedUserFaction(dbf) : new UserFaction(dbf)).collect(Collectors.toList())));
        }
    }

    public void manageFactions(HttpServerExchange exchange) throws Exception {
        if (exchange.getRelativePath().toLowerCase().trim().endsWith("/search")) { // eugh
            factionSearch(exchange);
            return;
        }
        User user = exchange.getAttachment(AuthReader.USER);
        JsonElement _data = exchange.getAttachment(JsonReader.ATTACHMENT_KEY);
        JsonObject dataObj = null;

        if (_data != null) {
            if (!_data.isJsonNull()) {
                if (!_data.isJsonObject()) {
                    sendBadRequest(exchange, "Invalid data");
                    return;
                }
                dataObj = _data.getAsJsonObject();
            }
        } // we don't require data for fetch/delete, so don't throw here.

        if (user == null) { // This is not a fetch endpoint, if the user doesn't exist we can't continue.
            send(StatusCodes.UNAUTHORIZED, exchange, "Not Authorized");
        } else if (exchange.getRequestMethod().equals(Methods.POST)) { // create a new faction
            if (user.isBanned()) {
                send(StatusCodes.FORBIDDEN, exchange, "Cannot create factions while banned");
            } else if (user.isFactionRestricted()) {
                send(StatusCodes.FORBIDDEN, exchange, "Your account is faction restricted and cannot creat new factions");
            } else {
                if (dataObj != null) {
                    String name = null;
                    String tag = null;
                    Integer color = null;
                    try {
                        name = dataObj.get("name").getAsString();
                    } catch (Exception ignored) {
                    }
                    try {
                        tag = dataObj.get("tag").getAsString();
                    } catch (Exception ignored) {
                    }
                    try {
                        color = dataObj.get("color").getAsInt();
                    } catch (Exception ignored) {
                    }
                    if (name == null || tag == null) {
                        sendBadRequest(exchange, "Invalid/Missing name/tag");
                    } else {
                        if (!Faction.ValidateTag(tag)) {
                            sendBadRequest(exchange, "Invalid/Disallowed Tag");
                        } else if (!Faction.ValidateName(name)) {
                            sendBadRequest(exchange, "Invalid/Disallowed Name");
                        } else if (App.getDatabase().getOwnedFactionCountForUID(user.getId()) >= App.getConfig().getInt("factions.maxOwned")) {
                            sendBadRequest(exchange, String.format("You've reached the maximum number of owned factions (%d).", App.getConfig().getInt("factions.maxOwned")));
                        } else if (App.getConfig().getInt("factions.minPixelsToCreate") > user.getAllTimePixelCount()) {
                            sendForbidden(exchange, String.format("You do not meet the minimum all-time pixel requirements to create a faction. The current minimum is %d.", App.getConfig().getInt("factions.minPixelsToCreate")));
                        } else {
                            Optional<Faction> faction = FactionManager.getInstance().create(name, tag, user.getId(), color);
                            if (faction.isPresent()) {
                                user.setDisplayedFactionMaybe(faction.get().getId());
                                sendObj(200, exchange, faction.get());
                            } else {
                                send(500, exchange, "Failed to create faction.");
                            }
                        }
                    }
                } else {
                    sendBadRequest(exchange, "Missing data");
                }
            }
        } else {
            int fid;
            try {
                fid = Integer.parseInt(exchange.getQueryParameters().get("fid").getFirst());
            } catch (Exception e) {
                sendBadRequest(exchange, "Invalid/Missing Faction ID");
                return;
            }

            Optional<Faction> _optFaction = FactionManager.getInstance().getByID(fid);
            if (!_optFaction.isPresent()) {
                sendBadRequest(exchange, "Invalid faction ID");
            } else {
                Faction faction = _optFaction.get();
                if (exchange.getRequestMethod().equals(Methods.GET)) { // serialize requested faction
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    exchange.getResponseSender().send(App.getGson().toJson((user.getId() == faction.getOwner()) ? new ExtendedUserFaction(faction) : new UserFaction(faction)));
                } else if (exchange.getRequestMethod().equals(Methods.PUT)) { // update requested faction
                    if (dataObj == null) {
                        sendBadRequest(exchange, "Missing data");
                    } else {
                        if (dataObj.has("displayed")) { // user is attempting to update displayed status
                            boolean displaying = false;
                            try {
                                displaying = dataObj.get("displayed").getAsBoolean();
                            } catch (Exception ignored) {
                            }
                            user.setDisplayedFaction(displaying ? faction.getId() : null);
                        } else if (dataObj.has("joinState")) { // user is attempting to leave/join
                            boolean joining = false;
                            try {
                                joining = dataObj.get("joinState").getAsBoolean();
                            } catch (Exception ignored) {
                            }
                            if (joining) {
                                if (faction.getOwner() == user.getId() || faction.fetchMembers().stream().anyMatch(fUser -> fUser.getId() == user.getId())) { // attempt to short-circuit the left-hand if we own the place
                                    sendBadRequest(exchange, "You are already a member of this faction.");
                                } else if (faction.fetchBans().stream().anyMatch(fUser -> fUser.getId() == user.getId())) {
                                    sendBadRequest(exchange, "You are banned from this faction. Please contact the owner and try again.");
                                } else {
                                    FactionManager.getInstance().joinFaction(fid, user.getId());
                                    user.setDisplayedFactionMaybe(fid);
                                }
                            } else {
                                if (faction.getOwner() == user.getId()) {
                                    sendBadRequest(exchange, "You cannot leave a faction you own. Transfer ownership first.");
                                } else {
                                    FactionManager.getInstance().leaveFaction(fid, user.getId());
                                    if (user.getDisplayedFaction() != null && user.getDisplayedFaction() == fid) {
                                        user.setDisplayedFaction(null, false, true); // displayed_faction is updated by #leaveFaction() already. we just need to invalidate the memcache.
                                    }
                                }
                            }
                        } else if (dataObj.has("banState") && dataObj.has("user")) {
                            boolean isBanned;
                            String opUser;
                            try {
                                isBanned = dataObj.get("banState").getAsBoolean();
                                opUser = dataObj.get("user").getAsString();
                            } catch (ClassCastException | IllegalStateException e) {
                                sendBadRequest(exchange, "Invalid banState and/or user supplied");
                                return;
                            }
                            if (opUser.trim().isEmpty()) {
                                sendBadRequest(exchange, "Invalid user supplied");
                            } else {
                                User userToModify = App.getUserManager().getByName(opUser);
                                if (userToModify == null || userToModify.getId() == user.getId()) {
                                    sendBadRequest(exchange, "Invalid user supplied");
                                } else {
                                    if (isBanned) { // we're attempting to ban a user. make sure they exist in the user list
                                        if (faction.fetchMembers().stream().anyMatch(fUser -> fUser.getId() == userToModify.getId())) {
                                            FactionManager.getInstance().banMemberFromFaction(faction.getId(), userToModify.getId());
                                        } else {
                                            sendBadRequest(exchange, "The requested user is not a member of this faction.");
                                        }
                                    } else {
                                        if (faction.fetchBans().stream().anyMatch(fUser -> fUser.getId() == userToModify.getId())) {
                                            FactionManager.getInstance().unbanMemberFromFaction(faction.getId(), userToModify.getId());
                                        } else {
                                            sendBadRequest(exchange, "The requested user is not banned from this faction.");
                                        }
                                    }
                                }
                            }
                        } else if (dataObj.has("newOwner")) {
                            String newOwner;
                            try {
                                newOwner = dataObj.get("newOwner").getAsString();
                            } catch (ClassCastException | IllegalStateException e) {
                                sendBadRequest(exchange, "Invalid newOwner supplied");
                                return;
                            }
                            if (user.getId() == faction.getOwner()) {
                                User userToModify = App.getUserManager().getByName(newOwner);
                                if (userToModify != null) {
                                    if (userToModify.isBanned()) {
                                        sendBadRequest(exchange, "This user is banned and cannot own any new factions.");
                                    } else if (userToModify.isFactionRestricted()) {
                                        sendBadRequest(exchange, "This user is faction restricted and cannot own any new factions.");
                                    } else if (App.getConfig().getInt("factions.minPixelsToCreate") > userToModify.getAllTimePixelCount()) {
                                        sendBadRequest(exchange, String.format("This user does not meet the minimum all-time pixel requirements to own a faction. The current minimum is %d.", App.getConfig().getInt("factions.minPixelsToCreate")));
                                    } else if (App.getDatabase().getOwnedFactionCountForUID(userToModify.getId()) >= App.getConfig().getInt("factions.maxOwned")) {
                                        sendBadRequest(exchange, String.format("This user has reached the maximum number of owned factions (%d).", App.getConfig().getInt("factions.maxOwned")));
                                    } else {
                                        if (faction.fetchMembers().stream().anyMatch(fUser -> fUser.getId() == userToModify.getId())) {
                                            App.getDatabase().setFactionOwnerForFID(faction.getId(), userToModify.getId());
                                            FactionManager.getInstance().invalidate(faction.getId());
                                        } else {
                                            sendBadRequest(exchange, "The requested user is not a member of the specified faction.");
                                        }
                                    }
                                } else {
                                    sendBadRequest(exchange, "The requested user does not exist.");
                                }
                            } else {
                                send(StatusCodes.FORBIDDEN, exchange, "You do not own this resource.");
                            }
                        } else { // user is attempting to update faction details
                            if (user.getId() != faction.getOwner()) {
                                send(StatusCodes.FORBIDDEN, exchange, "You do not own this resource");
                                return;
                            }
                            if (dataObj.has("name")) {
                                String _name = null;
                                try {
                                    _name = dataObj.get("name").getAsString();
                                } catch (Exception ignored) {}
                                if (_name != null && !_name.equals(faction.getName())) {
                                    if (Faction.ValidateName(_name)) {
                                        faction.setName(_name);
                                    } else {
                                        sendBadRequest(exchange, "Invalid/Disallowed Name");
                                        return;
                                    }
                                }
                            }
                            if (dataObj.has("tag")) {
                                String _tag = null;
                                try {
                                    _tag = dataObj.get("tag").getAsString();
                                } catch (Exception ignored) {}
                                if (_tag != null && !_tag.equals(faction.getTag())) {
                                    if (Faction.ValidateTag(_tag)) {
                                        faction.setTag(dataObj.get("tag").getAsString());
                                    } else {
                                        sendBadRequest(exchange, "Invalid/Disallowed Tag");
                                        return;
                                    }
                                }
                            }
                            if (dataObj.has("color")) {
                                Integer _color = null;
                                try {
                                    _color = dataObj.get("color").getAsInt();
                                } catch (Exception ignored) {}
                                if (_color != null && _color != faction.getColor()) {
                                    if (Faction.ValidateColor(_color)) {
                                        faction.setColor(_color);
                                    } else {
                                        sendBadRequest(exchange, "Invalid color");
                                        return;
                                    }
                                }
                            }
                            if (dataObj.has("owner")) {
                                String _owner = null;
                                try {
                                    _owner = dataObj.get("owner").getAsString();
                                } catch (Exception ignored) {}
                                if (_owner != null) {
                                    String final_owner = _owner;
                                    // verify that the member we're setting to owner actually exists in this faction.
                                    //  usernames are case-sensitive so we can safely use #equals
                                    User toSet = faction.fetchMembers().stream()
                                        .filter(n -> n.getName().equals(final_owner))
                                        .findFirst()
                                        .orElse(null);
                                    if (toSet != null) {
                                        faction.setOwner(toSet.getId());
                                    } else {
                                        sendBadRequest(exchange, "Invalid owner specified");
                                        return;
                                    }
                                }
                            }
                            FactionManager.getInstance().update(faction, true);
                        }
                    }
                    if (!exchange.isResponseStarted())
                        send(200, exchange, "OK");
                } else if (exchange.getRequestMethod().equals(Methods.DELETE)) { // remove requested faction
                    if (user.getId() != faction.getOwner()) {
                        send(StatusCodes.FORBIDDEN, exchange, "You do not own this resource");
                    } else {
                        FactionManager.getInstance().deleteByID(fid);
                        send(200, exchange, "OK");
                    }
                }
            }
        }
    }

    public void factionSearch(HttpServerExchange exchange) {
        Deque<String> _search = exchange.getQueryParameters().get("term");
        List<UserFaction> toReturn = new ArrayList<>();
        if (_search != null) {
            String search = _search.getFirst();
            String _after = exchange.getQueryParameters().getOrDefault("after", new ArrayDeque<>(Collections.singleton("0"))).getFirst();
            int after = 0;
            try {
                after = Integer.parseInt(_after);
            } catch (Exception ignored) {}
            toReturn = App.getDatabase().searchFactions(search, after, exchange.getAttachment(AuthReader.USER)).stream().map(UserFaction::new).collect(Collectors.toList());
        }
        sendObj(200, exchange, toReturn);
    }

    public void adminDeleteFaction(HttpServerExchange exchange) {
        if (!exchange.getRequestMethod().equals(Methods.POST)) {
            send(StatusCodes.METHOD_NOT_ALLOWED, exchange, StatusCodes.METHOD_NOT_ALLOWED_STRING);
            return;
        }

        Deque<String> _fid = exchange.getQueryParameters().get("fid");
        if (_fid == null) {
            sendBadRequest(exchange, "Missing faction ID (fid)");
            return;
        }

        int fid;
        try {
            fid = Integer.parseInt(_fid.getFirst());
        } catch (NumberFormatException ex) {
            sendBadRequest(exchange, "Faction ID is not a number");
            return;
        }

        Optional<Faction> _faction = FactionManager.getInstance().getByID(fid);
        if (!_faction.isPresent()) {
            sendBadRequest(exchange, "Faction with that ID doesn't exist");
            return;
        }

        FactionManager.getInstance().deleteByID(fid);
        send(200, exchange, "OK");
    }

    public void adminEditFaction(HttpServerExchange exchange) {
        if (!exchange.getRequestMethod().equals(Methods.POST)) {
            send(StatusCodes.METHOD_NOT_ALLOWED, exchange, StatusCodes.METHOD_NOT_ALLOWED_STRING);
            return;
        }

        Deque<String> _fid = exchange.getQueryParameters().get("fid");
        if (_fid == null) {
            sendBadRequest(exchange, "Missing faction ID (fid)");
            return;
        }

        int fid;
        try {
            fid = Integer.parseInt(_fid.getFirst());
        } catch (NumberFormatException ex) {
            sendBadRequest(exchange, "Faction ID is not a number");
            return;
        }

        Optional<Faction> _faction = FactionManager.getInstance().getByID(fid);
        if (!_faction.isPresent()) {
            sendBadRequest(exchange, "Faction with that ID doesn't exist");
            return;
        }

        Faction faction = _faction.get();

        FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);
        JsonElement _editQuery = exchange.getAttachment(JsonReader.ATTACHMENT_KEY);
        JsonObject editQuery;
        if ((_editQuery == null || _editQuery.isJsonNull() || !_editQuery.isJsonObject()) && data != null && data.contains("payload")) {
            try {
                _editQuery = new JsonParser().parse(data.getFirst("payload").getValue());
            } catch (Exception ignored) {}
        }
        if (_editQuery == null || _editQuery.isJsonNull() || !_editQuery.isJsonObject()) {
            sendBadRequest(exchange, "Missing edit query object attachment");
            return;
        }
        editQuery = _editQuery.getAsJsonObject();

        ArrayList<String> responseChanged = new ArrayList<>();
        HashMap<String, String> responseFailed = new HashMap<>();

        if (editQuery.has("name") && editQuery.get("name").isJsonPrimitive()) {
            String name = editQuery.get("name").getAsString();
            if (Faction.ValidateName(name)) {
                faction.setName(name);
                responseChanged.add("name");
            } else {
                responseFailed.put("name", "Invalid name");
            }
        }
        if (editQuery.has("tag") && editQuery.get("tag").isJsonPrimitive()) {
            String tag = editQuery.get("tag").getAsString();
            if (Faction.ValidateTag(tag)) {
                faction.setTag(tag);
                responseChanged.add("tag");
            } else {
                responseFailed.put("tag", "Invalid tag");
            }
        }
        if (editQuery.has("color") && editQuery.get("color").isJsonPrimitive()) {
            try {
                int color = editQuery.get("color").getAsInt();
                if (Faction.ValidateColor(color)) {
                    faction.setColor(color);
                    responseChanged.add("color");
                } else {
                    responseFailed.put("color", "Invalid color");
                }
            } catch (Exception ex) {
                responseFailed.put("color", "Invalid RGB int color");
            }
        }
        if (editQuery.has("owner") && editQuery.get("owner").isJsonPrimitive()) {
            try {
                int ownerUID = editQuery.get("owner").getAsInt();
                User user = App.getUserManager().getByID(ownerUID);
                if (user != null) {
                    faction.setOwner(ownerUID);
                    responseChanged.add("owner");

                    // Make new owner join the faction if not there already
                    App.getDatabase().joinFaction(fid, ownerUID);
                } else {
                    responseFailed.put("owner", "User not found");
                }
            } catch (Exception ex) {
                responseFailed.put("owner", "Invalid number");
            }
        }

        if (faction.isDirty().get()) {
            FactionManager.getInstance().update(faction, true);
        }

        JsonObject response = new JsonObject();
        response.add("changed", App.getGson().toJsonTree(responseChanged));
        response.add("failed", App.getGson().toJsonTree(responseFailed));
        sendObj(responseChanged.size() > 0 ? StatusCodes.OK : StatusCodes.BAD_REQUEST, exchange, response);
    }

    private void addServiceIfAvailable(String key, AuthService service) {
        if (service.use()) {
            services.put(key, service);
        }
    }

    private String getBanReason(HttpServerExchange exchange) {
        FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);
        FormData.FormValue reason = data.getFirst("reason");
        String reason_str = "";
        if (reason != null) {
            reason_str = reason.getValue();
        }
        return reason_str;
    }

    private int getRollbackTime(HttpServerExchange exchange) {
        FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);
        FormData.FormValue rollback = data.getFirst("rollback_time");
        String rollback_int = "0";
        if (rollback != null) {
            rollback_int = rollback.getValue();
        }
        return Integer.parseInt(rollback_int);
    }

    private boolean doLog(HttpServerExchange exchange) {
        FormData.FormValue nolog = exchange.getAttachment(FormDataParser.FORM_DATA).getFirst("nolog");
        return nolog == null;
    }

    private void setAuthCookie(HttpServerExchange exchange, String loginToken, int days) {
        Calendar pastCalendar = Calendar.getInstance();
        pastCalendar.add(Calendar.DATE, -1);
        exchange.setResponseCookie(
            new CookieImpl("pxls-token", "")
                .setPath("/")
                .setExpires(pastCalendar.getTime())
        );

        Calendar futureCalendar = Calendar.getInstance();
        futureCalendar.add(Calendar.DATE, days);
        String hostname = App.getConfig().getString("host");
        exchange.setResponseCookie(
            new CookieImpl("pxls-token", loginToken)
                .setHttpOnly(true)
                .setSameSiteMode((exchange.isSecure() ? CookieSameSiteMode.NONE : CookieSameSiteMode.LAX).toString())
                .setSecure(exchange.isSecure())
                .setPath("/")
                .setDomain("." + hostname)
                .setExpires(futureCalendar.getTime())
        );
        exchange.setResponseCookie(
            new CookieImpl("pxls-token", loginToken)
                .setHttpOnly(true)
                .setSameSiteMode((exchange.isSecure() ? CookieSameSiteMode.NONE : CookieSameSiteMode.LAX).toString())
                .setSecure(exchange.isSecure())
                .setPath("/")
                .setDomain(hostname)
                .setExpires(futureCalendar.getTime())
        );
    }

    public void ban(HttpServerExchange exchange) {
        User user = parseUserFromForm(exchange);
        User user_perform = exchange.getAttachment(AuthReader.USER);
        if (user != null && !user.hasPermission("user.ban")) {
            String time = "86400";
            FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);
            FormData.FormValue time_form = data.getFirst("time");
            if (time_form != null) {
                time = time_form.getValue();
            }
            if (doLog(exchange)) {
                App.getDatabase().insertAdminLog(user_perform.getId(), String.format("ban %s with reason: %s", user.getName(), getBanReason(exchange)));
            }
            user.ban(Integer.valueOf(time), getBanReason(exchange), getRollbackTime(exchange), user_perform);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/text");
            exchange.setStatusCode(200);
        } else {
            exchange.setStatusCode(400);
        }
    }

    public void unban(HttpServerExchange exchange) {
        User user_perform = exchange.getAttachment(AuthReader.USER);
        FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);
        if (data.contains("username")) {
            User unbanTarget = App.getUserManager().getByName(data.getFirst("username").getValue());
            if (unbanTarget != null) {
                String reason = "";
                if (data.contains("reason")) {
                    reason = data.getFirst("reason").getValue();
                }
                unbanTarget.unban(user_perform, reason);
                if (doLog(exchange)) {
                    App.getDatabase().insertAdminLog(user_perform.getId(), String.format("unban %s with reason %s", unbanTarget.getName(), reason.isEmpty() ? "(no reason provided)" : reason));
                }
                send(200, exchange, "User unbanned");
            } else {
                sendBadRequest(exchange, "Invalid user specified");
            }
        } else {
            sendBadRequest(exchange, "Missing username field");
        }
    }

    public void permaban(HttpServerExchange exchange) {
        User user = parseUserFromForm(exchange);
        User user_perform = exchange.getAttachment(AuthReader.USER);
        if (user != null && !user.hasPermission("user.permaban")) {
            user.ban(0, getBanReason(exchange), getRollbackTime(exchange), user_perform);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/text");
            if (doLog(exchange)) {
                App.getDatabase().insertAdminLog(user_perform.getId(), String.format("permaban %s with reason: %s", user.getName(), getBanReason(exchange)));
            }
            exchange.setStatusCode(200);
        } else {
            exchange.setStatusCode(400);
        }
    }

    public void shadowban(HttpServerExchange exchange) {
        User user = parseUserFromForm(exchange);
        User user_perform = exchange.getAttachment(AuthReader.USER);
        if (user != null && !user.hasPermission("user.shadowban")) {
            user.shadowBan(getBanReason(exchange), getRollbackTime(exchange), user_perform);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/text");
            if (doLog(exchange)) {
                App.getDatabase().insertAdminLog(user_perform.getId(), String.format("shadowban %s with reason: %s", user.getName(), getBanReason(exchange)));
            }
            exchange.setStatusCode(200);
        } else {
            exchange.setStatusCode(400);
        }
    }

    public void chatReport(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");

        if (!App.isChatEnabled()) {
            sendForbidden(exchange, "Chatting and chat actions are disabled");
            return;
        }

        User user = exchange.getAttachment(AuthReader.USER);
        if (user == null) {
            sendUnauthorized(exchange, "User must be logged in to report chat messages");
            return;
        }

        if (user.isBanned()) {
            sendForbidden(exchange, "Banned users cannot report chat messages");
            return;
        }

        FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);
        FormData.FormValue messageId;
        FormData.FormValue reportMessage;

        try {
            messageId = data.getFirst("cmid");
            reportMessage = data.getFirst("report_message");
        } catch (NullPointerException npe) {
            sendBadRequest(exchange, "Missing params");
            return;
        }

        if (messageId == null) {
            sendBadRequest(exchange, "Missing cmid");
            return;
        }

        int cmid;
        try {
            cmid = Integer.parseInt(messageId.getValue());
        } catch (NumberFormatException nfe) {
            sendBadRequest(exchange, "Invalid cmid");
            return;
        }

        DBChatMessage chatMessage = App.getDatabase().getChatMessageByID(cmid);
        if (chatMessage == null || reportMessage == null) {
            sendBadRequest(exchange, "Missing params");
            return;
        }

        String _reportMessage = reportMessage.getValue().trim();
        if (_reportMessage.length() > 2048) _reportMessage = _reportMessage.substring(0, 2048);
        Integer rid = App.getDatabase().insertChatReport(chatMessage.id, chatMessage.author_uid, user.getId(), _reportMessage);
        if (rid != null)
            App.getServer().broadcastToStaff(new ServerReceivedReport(rid, ServerReceivedReport.REPORT_TYPE_CHAT));

        send(200, exchange, null);
    }

    public void chatban(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");

        if (!App.isChatEnabled()) {
            sendForbidden(exchange, "Chatting and chat actions are disabled");
            return;
        }

        User user = exchange.getAttachment(AuthReader.USER);
        if (user == null) {
            sendBadRequest(exchange);
            return;
        }
        if (user.isBanned()) {
            sendBadRequest(exchange);
            return;
        }
        FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);

        FormData.FormValue cmidData;
        FormData.FormValue whoData;
        FormData.FormValue typeData;
        FormData.FormValue reasonData;
        FormData.FormValue removalData;
        FormData.FormValue banlengthData;

        String who;
        String type;
        String reason;
        Integer cmid;
        Integer removal;
        Integer banLength;

        boolean isPerma = false,
                isUnban = false;

        try {
            cmidData = data.getFirst("cmid");
            whoData = data.getFirst("who");
            typeData = data.getFirst("type");
            reasonData = data.getFirst("reason");
            removalData = data.getFirst("removalAmount");
            banlengthData = data.getFirst("banLength");
        } catch (NullPointerException npe) {
            sendBadRequest(exchange);
            return;
        }

        if (cmidData == null && whoData == null) {
            sendBadRequest(exchange);
            return;
        }

        if (typeData == null || reasonData == null || removalData == null || banlengthData == null) {
            sendBadRequest(exchange);
            return;
        }

        try {
            who = whoData == null ? null : whoData.getValue();
            type = typeData.getValue();
            reason = reasonData.getValue();
            cmid = cmidData == null ? null : Integer.parseInt(cmidData.getValue());
            removal = Integer.parseInt(removalData.getValue());
            banLength = Integer.parseInt(banlengthData.getValue());
        } catch (Exception e) {
            sendBadRequest(exchange);
            return;
        }

        isPerma = type.trim().equalsIgnoreCase("perma");
        isUnban = type.trim().equalsIgnoreCase("unban");
        User target = null;

        if (cmid != null) {
            DBChatMessage chatMessage = App.getDatabase().getChatMessageByID(cmid);
            if (chatMessage == null) {
                sendBadRequest(exchange);
                return;
            }

            target = App.getUserManager().getByID(chatMessage.author_uid);
        } else if (who != null) {
            target = App.getUserManager().getByName(who);
        }

        if (target == null) {
            sendBadRequest(exchange);
            return;
        }

        boolean _removal = removal == -1 || removal > 0;

        // TODO(netux): Fix infraestructure and allow to purge during snip mode
        if (_removal && App.getSnipMode()) {
            sendForbidden(exchange, "Cannot purge during snip mode");
            return;
        }

        Chatban chatban;
        if (isUnban) {
            chatban = Chatban.UNBAN(target, user, reason);
        } else {
            chatban = isPerma ?
                    Chatban.PERMA(target, user, reason, _removal, removal == -1 ? Integer.MAX_VALUE : removal) :
                    Chatban.TEMP(target, user, System.currentTimeMillis() + (banLength * 1000L), reason, _removal, removal == -1 ? Integer.MAX_VALUE : removal);
        }

        chatban.commit();

        exchange.setStatusCode(200);
        exchange.getResponseSender().send("{}");
        exchange.endExchange();
    }

    public void deleteChatMessage(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");

        if (!App.isChatEnabled()) {
            sendForbidden(exchange, "Chatting and chat actions are disabled");
            return;
        }

        User user = exchange.getAttachment(AuthReader.USER);
        if (user == null) {
            send(StatusCodes.FORBIDDEN, exchange, "");
            return;
        }

        if (user.isBanned()) {
            send(StatusCodes.FORBIDDEN, exchange, "");
            return;
        }

        FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);
        FormData.FormValue chatId = null;
        String reason = null;

        try {
            chatId = data.getFirst("cmid");
        } catch (NullPointerException npe) {
            sendBadRequest(exchange, "Message cmid invalid/missing");
            return;
        }

        if (chatId == null) {
            sendBadRequest(exchange, "Message cmid missing");
            return;
        }

        int cmid;
        try {
            cmid = Integer.parseInt(chatId.getValue());
        } catch (NumberFormatException nfe) {
            sendBadRequest(exchange, "Bad CMID");
            return;
        }

        DBChatMessage chatMessage = App.getDatabase().getChatMessageByID(cmid);
        if (chatMessage == null) {
            sendBadRequest(exchange, "Message didn't exist");
            return;
        }

        User author = App.getUserManager().getByID(chatMessage.author_uid);
        if (author == null) {
            sendBadRequest(exchange, "Author was null");
            return;
        }

        try {
            FormData.FormValue formReason = data.getFirst("reason");
            reason = formReason.getValue();
        } catch (NullPointerException npe) {
            reason = "";
        }

        App.getDatabase().purgeChatID(author, user, chatMessage.id, reason, true);

        send(StatusCodes.OK, exchange, "");
    }

    public void chatPurge(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");

        if (!App.isChatEnabled()) {
            sendForbidden(exchange, "Chatting and chat actions are disabled");
            return;
        }

        // TODO(netux): Fix infraestructure and allow to purge during snip mode
        if (App.getSnipMode()) {
            sendForbidden(exchange, "Cannot purge chat during snip mode");
            return;
        }

        User user = exchange.getAttachment(AuthReader.USER);
        if (user == null) {
            sendBadRequest(exchange);
            return;
        }

        if (user.isBanned()) {
            sendBadRequest(exchange);
            return;
        }

        FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);
        FormData.FormValue targetData = null;
        FormData.FormValue reasonData = null;

        try {
            targetData = data.getFirst("who");
            reasonData = data.getFirst("reason");
        } catch (NullPointerException npe) {
            sendBadRequest(exchange);
            return;
        }

        if (targetData == null) {
            sendBadRequest(exchange);
            return;
        }

        User target = App.getUserManager().getByName(targetData.getValue());
        if (target == null) {
            sendBadRequest(exchange);
            return;
        }

        App.getDatabase().purgeChat(target, user, Integer.MAX_VALUE, reasonData.getValue(), true);

        exchange.setStatusCode(200);
        exchange.getResponseSender().send("{}");
        exchange.endExchange();
    }

    public void chatColorChange(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");

        if (!App.isChatEnabled()) {
            sendForbidden(exchange, "Chatting and chat actions are disabled");
            return;
        }

        User user = exchange.getAttachment(AuthReader.USER);
        if (user == null) {
            sendBadRequest(exchange);
            return;
        }

        FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);

        FormData.FormValue nameColor = data.getFirst("color");
        if (nameColor == null || nameColor.getValue().trim().isEmpty()) {
            sendBadRequest(exchange);
            return;
        }

        try {
            int t = Integer.parseInt(nameColor.getValue());
            if (t >= -3 && t < App.getPalette().getColors().size()) {
                var hasAllDonatorColors = user.hasPermission("chat.usercolor.donator") || user.hasPermission("chat.usercolor.donator.*");
                if (t == -1 && !user.hasPermission("chat.usercolor.rainbow")) {
                    sendBadRequest(exchange, "Color reserved for staff members");
                    return;
                } else if (t == -2 && !(hasAllDonatorColors || user.hasPermission("chat.usercolor.donator.green"))) {
                    sendBadRequest(exchange, "Color reserved for donators");
                    return;
                } else if (t == -3 && !(hasAllDonatorColors || user.hasPermission("chat.usercolor.donator.gray"))) {
                    sendBadRequest(exchange, "Color reserved for donators");
                    return;
                }

                user.setChatNameColor(t, true, !App.getSnipMode());

                exchange.setStatusCode(200);
                exchange.getResponseSender().send("{}");
                exchange.endExchange();
            } else {
                sendBadRequest(exchange, "Color index out of bounds");
                return;
            }
        } catch (NumberFormatException nfe) {
            sendBadRequest(exchange, "Invalid color index");
            return;
        }
    }

    public void forceNameChange(HttpServerExchange exchange) { //this is the admin endpoint which targets another user.
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        User user = exchange.getAttachment(AuthReader.USER);
        if (user == null) {
            sendBadRequest(exchange, "No authenticated users found");
            return;
        }

        FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);
        String userName = null;
        String newName = null;
        try {
            userName = data.getFirst("user").getValue();
            newName = data.getFirst("newName").getValue();
        } catch (Exception npe) {
            sendBadRequest(exchange, "Missing either 'user' or 'newName' fields");
            return;
        }

        if (!validateUsername(userName)) {
            sendBadRequest(exchange, "Username failed validation");
            return;
        }

        User toUpdate = App.getUserManager().getByName(userName);
        if (toUpdate == null) {
            sendBadRequest(exchange, "Invalid user provided");
            return;
        }

        String oldName = toUpdate.getName();
        if (toUpdate.updateUsername(newName, true)) {
            App.getDatabase().insertAdminLog(user.getId(), String.format("Changed %s's name to %s (uid: %d)", oldName, newName, toUpdate.getId()));
            toUpdate.setRenameRequested(false);
            App.getServer().send(toUpdate, new ServerRenameSuccess(toUpdate.getName()));
            exchange.setStatusCode(200);
            exchange.getResponseSender().send("{}");
            exchange.endExchange();
        } else {
            sendBadRequest(exchange, "Failed to update username. Possible reasons for this include the new username is already taken, the user being updated was not flagged for rename, or an internal error occurred.");
        }
    }

    public void execNameChange(HttpServerExchange exchange) { //this is the endpoint for normal users
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        User user = exchange.getAttachment(AuthReader.USER);
        if (user == null) {
            sendBadRequest(exchange, "No authenticated users found");
            return;
        }
        FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);
        String newName = null;
        try {
            newName = data.getFirst("newName").getValue();
        } catch (Exception npe) {
            sendBadRequest(exchange, "Missing either 'user' or 'newName' fields");
            return;
        }

        if (!validateUsername(newName)) {
            sendBadRequest(exchange, "Username failed validation");
            return;
        }

        String oldName = user.getName();
        if (user.updateUsername(newName)) {
            App.getDatabase().insertServerReport(user.getId(), String.format("User %s just changed their name to %s.", oldName, user.getName()));
            user.setRenameRequested(false);
            App.getServer().send(user, new ServerRenameSuccess(user.getName()));
            exchange.setStatusCode(200);
            exchange.getResponseSender().send("{}");
            exchange.endExchange();
        } else {
            sendBadRequest(exchange, "Failed to update username. Possible reasons for this include the new username is already taken, the user being updated was not flagged for rename, or an internal error occurred.");
        }
    }

    public void flagNameChange(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        User user = exchange.getAttachment(AuthReader.USER);
        if (user == null) {
            sendBadRequest(exchange, "No authenticated user could be found");
            return;
        }

        FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);
        String userName = null;
        boolean isRequested = true;
        try {
            userName = data.getFirst("user").getValue();
        } catch (Exception npe) {
            npe.printStackTrace();
            sendBadRequest(exchange, "Missing 'user' field");
            return;
        }
        try {
            isRequested = data.getFirst("flagState").getValue().equalsIgnoreCase("true");
        } catch (Exception e) {
            //ignored
        }

        User toFlag = App.getUserManager().getByName(userName);
        if (toFlag == null) {
            sendBadRequest(exchange, "Invalid user provided");
            return;
        }

        toFlag.setRenameRequested(isRequested);
        App.getDatabase().insertAdminLog(user.getId(), String.format("%s %s (%d) for name change", isRequested ? "Flagged" : "Unflagged", toFlag.getName(), toFlag.getId()));

        exchange.setStatusCode(200);
        exchange.getResponseSender().send("{}");
        exchange.endExchange();
    }

    public void discordNameChange(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        User user = exchange.getAttachment(AuthReader.USER);
        if (user == null) {
            sendBadRequest(exchange, "No authenticated user could be found");
            return;
        }

        FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);
        String discordName = null;
        try {
            discordName = data.getFirst("discordName").getValue();
            if (discordName.equalsIgnoreCase("")) {
                discordName = null;
            }
        } catch (Exception npe) {
            npe.printStackTrace();
            sendBadRequest(exchange, "Missing 'discordName' field");
            return;
        }
        boolean isDeleteRequest = discordName == null;

        if (isDeleteRequest && user.getDiscordName() == null) {
            send(StatusCodes.CONFLICT, exchange, "Discord name is already unset.");
            return;
        }

        if (!isDeleteRequest && user.getDiscordName() != null && user.getDiscordName().equals(discordName)) {
            send(StatusCodes.CONFLICT, exchange, "Discord name is already set to the requested name.");
            return;
        }

        if (discordName != null && !discordName.matches("^.{2,32}#\\d{4}$")) {
            sendBadRequest(exchange, "name isn't in the format '{name}#{discriminator}'");
            return;
        }

        if (discordName == null) { //user is deleting name, bypass ratelimit check
            user.setDiscordName(null);
            send(StatusCodes.OK, exchange, "Name removed");
        } else {
            int remaining = RateLimitFactory.getTimeRemaining("http:discordName", exchange.getAttachment(IPReader.IP));
            if (remaining == 0) {
                user.setDiscordName(discordName);
                send(StatusCodes.OK, exchange, "Name updated");
            } else {
                send(StatusCodes.TOO_MANY_REQUESTS, exchange, "Hit max attempts. Try again in " + remaining + "s");
            }
        }
    }

    public void setFactionBlocked(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        User user = exchange.getAttachment(AuthReader.USER);
        if (user != null) {
            FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);
            if (data != null) {
                if (data.contains("username") && data.contains("faction_restricted")) {
                    String username = data.getFirst("username").getValue();
                    boolean isFactionBlocked = data.getFirst("faction_restricted").getValue().equalsIgnoreCase("true");
                    User fromForm = App.getUserManager().getByName(username);
                    if (fromForm != null) {
                        fromForm.setFactionBlocked(isFactionBlocked, true);
                        App.getDatabase().insertAdminLog(user.getId(), String.format("Set %s's faction_restricted state to %s", fromForm.getName(), isFactionBlocked));
                        send(StatusCodes.OK, exchange, "OK");
                    } else {
                        sendBadRequest(exchange, "The user does not exist");
                    }
                } else {
                    sendBadRequest(exchange, "Missing params");
                }
            } else {
                sendBadRequest(exchange, "Missing form data");
            }
        } else {
            sendBadRequest(exchange, "No authenticated users found");
        }
    }

    public void createNotification(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");

        User user = exchange.getAttachment(AuthReader.USER);
        if (user == null) {
            sendBadRequest(exchange, "No authenticated users found");
            return;
        }
        FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);
        String title = null;
        String body = null;
        Long expiry = 0L;
        boolean discord = false;
        try {
            title = data.getFirst("txtTitle").getValue();
            body = data.getFirst("txtBody").getValue();
            expiry = 0L;
        } catch (Exception npe) {
            sendBadRequest(exchange, "Missing either 'txtTitle' or 'txtBody' fields");
            return;
        }
        if (data.contains("discord")) {
            try {
                discord = data.getFirst("discord").getValue().trim().equalsIgnoreCase("true");
            } catch (Exception e) {
                sendBadRequest(exchange, "Invalid discord value");
                return;
            }
        }
        if (data.contains("expiry")) {
            try {
                expiry = Long.parseLong(data.getFirst("expiry").getValue().trim());
            } catch (Exception e) {
                sendBadRequest(exchange, "Invalid expiry value");
                return;
            }
        }
        try {
            int notifID = App.getDatabase().createNotification(user.getId(), title, body, Instant.ofEpochMilli(expiry).getEpochSecond());
            App.getServer().broadcast(new ServerNotification(App.getDatabase().getNotification(notifID))); //re-fetch to ensure we're returning exact time and expiry 'n whatnot from the database.
        } catch (Exception e) {
            e.printStackTrace();
            send(StatusCodes.INTERNAL_SERVER_ERROR, exchange, "Failed to create notification");
            return;
        }
        if (discord) {
            handleNotificationWebhook(exchange, title, body);
        } else {
            send(StatusCodes.OK, exchange, "");
        }
    }

    public void sendNotificationToDiscord(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        User user = exchange.getAttachment(AuthReader.USER);
        if (user == null) {
            sendBadRequest(exchange, "No authenticated users found");
            return;
        }
        FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);
        int notificationID = -1;
        if (!data.contains("id")) {
            sendBadRequest(exchange, "Missing notification id");
            return;
        }
        try {
            notificationID = Integer.parseInt(data.getFirst("id").getValue().trim());
        } catch (Exception e) {
            sendBadRequest(exchange, "Invalid notification id");
        }
        DBNotification notif = App.getDatabase().getNotification(notificationID);
        if (notif == null) {
            send(StatusCodes.NOT_FOUND, exchange, "Notification doesn't exist");
        } else {
            handleNotificationWebhook(exchange, notif.title, notif.content);
        }
    }

    public void setNotificationExpired(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        User user = exchange.getAttachment(AuthReader.USER);
        if (user == null) {
            sendBadRequest(exchange, "No authenticated users found");
            return;
        }
        FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);
        int notificationID = -1;
        boolean shouldBeExpired = false;
        if (!data.contains("id")) {
            sendBadRequest(exchange, "Missing notification id");
            return;
        }
        try {
            notificationID = Integer.parseInt(data.getFirst("id").getValue().trim());
        } catch (Exception e) {
            sendBadRequest(exchange, "Invalid notification id");
        }
        try {
            notificationID = Integer.parseInt(data.getFirst("id").getValue().trim());
        } catch (Exception e) {
            sendBadRequest(exchange, "Invalid notification id");
        }
        if (App.getDatabase().getNotification(notificationID) == null) {
            send(StatusCodes.NOT_FOUND, exchange, "Notification doesn't exist");
            return;
        }
        try {
            shouldBeExpired = data.getFirst("expired").getValue().trim().equalsIgnoreCase("true");
        } catch (Exception e) {
            sendBadRequest(exchange, "Invalid 'expired'");
            return;
        }
        App.getDatabase().setNotificationExpiry(notificationID, shouldBeExpired ? 1L : 0L);
        send(StatusCodes.OK, exchange, "");
    }

    private void handleNotificationWebhook(HttpServerExchange exchange, String title, String body) {
        String webhookURL = App.getConfig().getString("webhooks.announcements");
        if (webhookURL.isEmpty()) {
            send(StatusCodes.INTERNAL_SERVER_ERROR, exchange, "No announcement webhook is configured");
        } else {
            if (SimpleDiscordWebhook.forWebhookURL(webhookURL).content(String.format("**%s**\n\n%s", title, body)).execute()) {
                send(StatusCodes.OK, exchange, "");
            } else {
                send(StatusCodes.INTERNAL_SERVER_ERROR, exchange, "Failed to execute discord webhook");
            }
        }
    }

    public void notificationsList(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.setStatusCode(200);
        exchange.getResponseSender().send(App.getGson().toJson(App.getDatabase().getNotifications(false)));
        exchange.endExchange();
    }

    private void sendBadRequest(HttpServerExchange exchange) {
        sendBadRequest(exchange, "");
    }

    private void sendBadRequest(HttpServerExchange exchange, String details) {
        send(StatusCodes.BAD_REQUEST, exchange, details);
    }

    private void sendUnauthorized(HttpServerExchange exchange) {
        sendUnauthorized(exchange, "");
    }

    private void sendUnauthorized(HttpServerExchange exchange, String details) {
        send(StatusCodes.UNAUTHORIZED, exchange, details);
    }

    private void sendForbidden(HttpServerExchange exchange) {
        sendForbidden(exchange, "");
    }

    private void sendForbidden(HttpServerExchange exchange, String details) {
        send(StatusCodes.FORBIDDEN, exchange, details);
    }

    private void send(int statusCode, HttpServerExchange exchange, String details) {
        boolean isSuccess = statusCode >= 200 && statusCode < 300;

        JsonObject toSend = new JsonObject();
        toSend.addProperty("success", isSuccess);
        toSend.addProperty("message", StatusCodes.getReason(statusCode));
        toSend.addProperty("details", details == null ? "" : details);

        exchange.setStatusCode(statusCode);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(App.getGson().toJson(toSend));
        exchange.endExchange();
    }

    private void sendObj(int statusCode, HttpServerExchange exchange, Object o) {
        boolean isSuccess = statusCode >= 200 && statusCode < 300;

        JsonObject toSend = new JsonObject();
        toSend.addProperty("success", isSuccess);
        toSend.addProperty("message", StatusCodes.getReason(statusCode));
        toSend.add("details", App.getGson().toJsonTree(o));

        exchange.setStatusCode(statusCode);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(App.getGson().toJson(toSend));
        exchange.endExchange();
    }

    public void check(HttpServerExchange exchange) {
        FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);
        if (data != null) {
            // lookups are only nonce when in snip mode, which is typically only going to happen once or twice a year (at the time of writing). we'll short circuit on username most of the time.
            User user = null;
            if (data.contains("username")) {
                user = App.getUserManager().getByName(data.getFirst("username").getValue());
            } else if (data.contains("cmid")) {
                Integer i = null;
                try {
                    i = Integer.parseInt(data.getFirst("cmid").getValue());
                } catch (NullPointerException npe) {
                    sendBadRequest(exchange, "Missing CMID");
                } catch (NumberFormatException nfe) {
                    sendBadRequest(exchange, "Bad CMID");
                }
                if (i != null) {
                    DBChatMessage chatMessage = App.getDatabase().getChatMessageByID(i);
                    if (chatMessage != null) {
                        user = App.getUserManager().getByID(chatMessage.author_uid);
                    }
                }
            }

            if (user != null) {
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                exchange.getResponseSender().send(App.getGson().toJson(
                    new ServerUserInfo(
                        user.getName(),
                        user.getLogin(),
                        user.getAllRoles(),
                        user.getPixelCount(),
                        user.getAllTimePixelCount(),
                        user.isBanned(),
                        user.getBanExpiryTime(),
                        user.getBanReason(),
                        user.getLogin().split(":")[0],
                        user.getPlaceOverrides(),
                        !user.canChat(),
                        App.getDatabase().getChatBanReason(user.getId()),
                        user.isPermaChatbanned(),
                        user.getChatbanExpiryTime(),
                        user.isRenameRequested(true),
                        user.getDiscordName(),
                        user.getChatNameColor()
                    )));
            } else {
                exchange.setStatusCode(400);
            }
        }
    }

    public void signUp(HttpServerExchange exchange) {
        if (!App.getRegistrationEnabled()) {
            respond(exchange, StatusCodes.UNAUTHORIZED, new space.pxls.server.packets.http.Error("registration_disabled", "Registration has been disabled"));
            return;
        }
        FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);
        FormData.FormValue nameVal = data.getFirst("username");
        FormData.FormValue tokenVal = data.getFirst("token");
        if (nameVal == null || tokenVal == null) {
            respond(exchange, StatusCodes.BAD_REQUEST, new space.pxls.server.packets.http.Error("bad_params", "Missing parameters"));
            return;
        }

        String name = nameVal.getValue();
        String token = tokenVal.getValue();
        if (token.isEmpty()) {
            respond(exchange, StatusCodes.BAD_REQUEST, new space.pxls.server.packets.http.Error("bad_token", "Missing signup token"));
            return;
        } else if (name.isEmpty()) {
            respond(exchange, StatusCodes.BAD_REQUEST, new space.pxls.server.packets.http.Error("bad_username", "Username may not be empty"));
            return;
        } else if (!name.matches("[a-zA-Z0-9_\\-]+")) {
            respond(exchange, StatusCodes.BAD_REQUEST, new space.pxls.server.packets.http.Error("bad_username", "Username contains invalid characters"));
            return;
        } else if (!App.getUserManager().isValidSignupToken(token)) {
            respond(exchange, StatusCodes.BAD_REQUEST, new space.pxls.server.packets.http.Error("bad_token", "Invalid signup token"));
            return;
        }

        String ip = exchange.getAttachment(IPReader.IP);
        User user = App.getUserManager().signUp(name, token, ip);

        if (user == null) {
            respond(exchange, StatusCodes.BAD_REQUEST, new space.pxls.server.packets.http.Error("bad_username", "Username taken, try another?"));
            return;
        }

        // Do additional checks below:
        List<String> reports = new ArrayList<String>();

        // NOTE: Dupe IP checks are done on auth, not just signup.

        // check username for filter hits
        if (App.getConfig().getBoolean("textFilter.enabled") && TextFilter.getInstance().filterHit(name)) {
            reports.add(String.format("Username filter hit on \"%s\"", name));
        }

        for (String reportMessage : reports) {
            Integer rid = App.getDatabase().insertServerReport(user.getId(), reportMessage);
            if (rid != null) {
                App.getServer().broadcastToStaff(new ServerReceivedReport(rid, ServerReceivedReport.REPORT_TYPE_CANVAS));
            }
        }

        String loginToken = App.getUserManager().logIn(user, ip);
        setAuthCookie(exchange, loginToken, 24);
        respond(exchange, StatusCodes.OK, new SignUpResponse(loginToken));
    }

    public void auth(HttpServerExchange exchange) throws UnirestException {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this::auth);
            return;
        }

        String id = exchange.getRelativePath().substring(1);

        AuthService service = services.get(id);
        if (service != null && service.use()) {

            // Verify the given OAuth state, to make sure people don't double-send requests
            Deque<String> stateQ = exchange.getQueryParameters().get("state");

            String state_ = "";
            if (stateQ != null) {
                state_ = stateQ.element();
            }
            String[] stateArray = state_.split("\\|");
            String state = stateArray[0];
            boolean redirect = false;
            if (stateArray.length > 1) {
                redirect = stateArray[1].equals("redirect");
            } else {
                // check for cookie...
                Cookie redirectCookie = exchange.getRequestCookies().get("pxls-auth-redirect");
                redirect = redirectCookie != null;
            }
            // let's just delete the redirect cookie
            Calendar pastCalendar = Calendar.getInstance();
            pastCalendar.add(Calendar.DATE, -1);
            exchange.setResponseCookie(
                new CookieImpl("pxls-auth-redirect", "")
                    .setPath("/")
                    .setExpires(pastCalendar.getTime())
            );

            if (!redirect && exchange.getQueryParameters().get("json") == null) {
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html");
                exchange.getResponseSender().send("<!DOCTYPE html><html><head><title>Pxls Login</title><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=0\"/></head><body><a style=\"font-size:2em;font-weight:bold;\" href=\"" + exchange.getRequestURI() + "?" + exchange.getQueryString() + "\">Finish Login</a><br>Hold down long on that link and select to open with pxls app.</body>");

                return;
            }

            // Check for errors reported by server
            if (exchange.getQueryParameters().containsKey("error")) {
                String error = exchange.getQueryParameters().get("error").element();
                if (error.equals("access_denied")) error = "Authentication denied by user";
                if (redirect) {
                    redirect(exchange, "/auth_done.html?nologin=1");
                } else {
                    respond(exchange, StatusCodes.UNAUTHORIZED, new space.pxls.server.packets.http.Error("oauth_error", error));
                }
                return;
            }

            if (!service.verifyState(state)) {
                respond(exchange, StatusCodes.BAD_REQUEST, new space.pxls.server.packets.http.Error("bad_state", "Invalid state token"));
                return;
            }

            // Get the one-time authorization code from the request
            String code = extractOAuthCode(exchange);
            if (code == null) {
                if (redirect) {
                    redirect(exchange, "/auth_done.html?nologin=1");
                } else {
                    respond(exchange, StatusCodes.BAD_REQUEST, new space.pxls.server.packets.http.Error("bad_code", "No OAuth code specified"));
                }
                return;
            }

            // Get a more persistent user token
            String token = service.getToken(code);
            if (token == null) {
                respond(exchange, StatusCodes.UNAUTHORIZED, new space.pxls.server.packets.http.Error("bad_code", "OAuth code invalid"));
                return;
            }

            // And get an account identifier from that
            String identifier;
            try {
                identifier = service.getIdentifier(token);
            } catch (AuthService.InvalidAccountException e) {
                respond(exchange, StatusCodes.UNAUTHORIZED, new space.pxls.server.packets.http.Error("invalid_account", e.getMessage()));
                return;
            }

            if (identifier != null) {
                String login = id + ":" + identifier;
                User user = App.getUserManager().getByLogin(login);
                // If there is no user with that identifier, we make a signup token and tell the client to sign up with that token
                if (user == null) {
                    if (service.isRegistrationEnabled()) {
                        String signUpToken = App.getUserManager().generateUserCreationToken(login);
                        if (redirect) {
                            redirect(exchange, String.format("/auth_done.html?token=%s&signup=true", encodedURIComponent(signUpToken)));
                        } else {
                            respond(exchange, StatusCodes.OK, new AuthResponse(signUpToken, true));
                        }
                    } else {
                        respond(exchange, StatusCodes.UNAUTHORIZED, new space.pxls.server.packets.http.Error("invalid_service_operation", "Registration is currently disabled for this service. Please try one of the other ones."));
                    }
                } else {
                    // We need the IP for logging/db purposes
                    String ip = exchange.getAttachment(IPReader.IP);
                    String loginToken = App.getUserManager().logIn(user, ip);
                    setAuthCookie(exchange, loginToken, 24);
                    if (redirect) {
                        redirect(exchange, String.format("/auth_done.html?token=%s&signup=false", encodedURIComponent(loginToken)));
                    } else {
                        respond(exchange, StatusCodes.OK, new AuthResponse(loginToken, false));
                    }
                }
            } else {
                respond(exchange, StatusCodes.BAD_REQUEST, new space.pxls.server.packets.http.Error("bad_service", "No auth service named " + id));
            }
        } else {
            respond(exchange, StatusCodes.BAD_REQUEST, new Error("bad_service", "No auth service named " + id));
        }
    }

    private String extractOAuthCode(HttpServerExchange exchange) {
        // Most implementations just add a "code" parameter
        Deque<String> code = exchange.getQueryParameters().get("code");
        if (code != null && !code.isEmpty()) return code.element();

        // OAuth 1 still uses these parameters
        Deque<String> oauthToken = exchange.getQueryParameters().get("oauth_token");
        Deque<String> oauthVerifier = exchange.getQueryParameters().get("oauth_verifier");

        if (oauthToken == null || oauthVerifier == null || oauthToken.isEmpty() || oauthVerifier.isEmpty()) return null;
        return oauthToken.element() + "|" + oauthVerifier.element();
    }

    public void signIn(HttpServerExchange exchange) {
        String id = exchange.getRelativePath().substring(1);
        boolean redirect = exchange.getQueryParameters().get("redirect") != null;

        AuthService service = services.get(id);
        if (service != null) {
            String state = service.generateState();
            if (redirect) {
                exchange.setResponseCookie(
                    new CookieImpl("pxls-auth-redirect", "1")
                        .setSameSiteMode((exchange.isSecure() ? CookieSameSiteMode.NONE : CookieSameSiteMode.LAX).toString())
                        .setSecure(exchange.isSecure())
                        .setPath("/")
                );
                redirect(exchange, service.getRedirectUrl(state + "|redirect"));
            } else {
                respond(exchange, StatusCodes.OK, new SignInResponse(service.getRedirectUrl(state + "|json")));
            }
        } else {
            respond(exchange, StatusCodes.BAD_REQUEST, new Error("bad_service", "No auth method named " + id));
        }
    }

    public void info(HttpServerExchange exchange) {
        User user = exchange.getAttachment(AuthReader.USER);

        exchange.getResponseHeaders()
                .add(HttpString.tryFromString("Content-Type"), "application/json")
                .add(HttpString.tryFromString("Access-Control-Allow-Origin"), "*");
        exchange.getResponseSender().send(App.getGson().toJson(new CanvasInfo(
            App.getCanvasCode(),
            App.getWidth(),
            App.getHeight(),
            App.getPalette().getColors(),
            App.getConfig().getString("captcha.key"),
            (int) App.getConfig().getDuration("board.heatmapCooldown", TimeUnit.SECONDS),
            (int) App.getConfig().getInt("stacking.maxStacked"),
            services,
            App.getRegistrationEnabled(),
            App.isChatEnabled(),
            Math.min(App.getConfig().getInt("chat.characterLimit"), 2048),
            App.getConfig().getBoolean("chat.canvasBanRespected"),
            App.getConfig().getStringList("chat.bannerText"),
            App.getSnipMode(),
            App.getConfig().getList("chat.customEmoji").unwrapped()
        )));
    }

    public void data(HttpServerExchange exchange) {
        exchange.getResponseHeaders()
                .put(Headers.CONTENT_TYPE, "application/binary")
                .put(HttpString.tryFromString("Access-Control-Allow-Origin"), "*");

        // let's also update the cookie, if present. This place will get called frequent enough
        Cookie tokenCookie = exchange.getRequestCookies().get("pxls-token");
        if (tokenCookie != null) {
            setAuthCookie(exchange, tokenCookie.getValue(), 24);
        }

        exchange.getResponseSender().send(ByteBuffer.wrap(App.getBoardData()));
    }

    public void initialdata(HttpServerExchange exchange) {
        exchange.getResponseHeaders()
                .put(Headers.CONTENT_TYPE, "application/binary")
                .put(HttpString.tryFromString("Access-Control-Allow-Origin"), "*");

        exchange.getResponseSender().send(ByteBuffer.wrap(App.getDefaultBoardData()));
    }

    public void heatmap(HttpServerExchange exchange) {
        exchange.getResponseHeaders()
                .put(Headers.CONTENT_TYPE, "application/binary")
                .put(HttpString.tryFromString("Access-Control-Allow-Origin"), "*");
        exchange.getResponseSender().send(ByteBuffer.wrap(App.getHeatmapData()));
    }

    public void virginmap(HttpServerExchange exchange) {
        exchange.getResponseHeaders()
                .put(Headers.CONTENT_TYPE, "application/binary")
                .put(HttpString.tryFromString("Access-Control-Allow-Origin"), "*");
        exchange.getResponseSender().send(ByteBuffer.wrap(App.getVirginmapData()));
    }

    public void placemap(HttpServerExchange exchange) {
        exchange.getResponseHeaders()
                .put(Headers.CONTENT_TYPE, "application/binary")
                .put(HttpString.tryFromString("Access-Control-Allow-Origin"), "*");
        exchange.getResponseSender().send(ByteBuffer.wrap(App.getPlacemapData()));
    }

    public void logout(HttpServerExchange exchange) {
        Cookie tokenCookie = exchange.getRequestCookies().get("pxls-token");

        if (tokenCookie != null) {
            App.getUserManager().logOut(tokenCookie.getValue());
        }

        setAuthCookie(exchange, "", -1);
        respond(exchange, StatusCodes.OK, new EmptyResponse());
    }

    public void lookup(HttpServerExchange exchange) {
        User user = exchange.getAttachment(AuthReader.USER);

        Deque<String> xq = exchange.getQueryParameters().get("x");
        Deque<String> yq = exchange.getQueryParameters().get("y");

        if (xq == null || xq.isEmpty() || yq == null || yq.isEmpty()) {
            exchange.setStatusCode(StatusCodes.BAD_REQUEST);
            exchange.endExchange();
            return;
        }

        int x = (int) Math.floor(Float.parseFloat(xq.element()));
        int y = (int) Math.floor(Float.parseFloat(yq.element()));
        if (x < 0 || x >= App.getWidth() || y < 0 || y >= App.getHeight()) {
            exchange.setStatusCode(StatusCodes.BAD_REQUEST);
            exchange.endExchange();
            return;
        }

        exchange.getResponseHeaders()
                .put(Headers.CONTENT_TYPE, "application/json")
                .put(HttpString.tryFromString("Access-Control-Allow-Origin"), "*");
        if (user == null) {
            App.getDatabase().insertLookup(null, exchange.getAttachment(IPReader.IP));
        } else {
            App.getDatabase().insertLookup(user.getId(), exchange.getAttachment(IPReader.IP));
        }

        Lookup lookup;
        if (user != null && user.hasPermission("board.check")) {
            lookup = ExtendedLookup.fromDB(App.getDatabase().getFullPixelAt(x, y).orElse(null));
        } else {
            lookup = Lookup.fromDB(App.getDatabase().getPixelAt(x, y).orElse(null));
            if (lookup != null && App.getSnipMode()) {
                lookup = lookup.asSnipRedacted();
            }
        }
        exchange.getResponseSender().send(App.getGson().toJson(lookup));
    }

    public void report(HttpServerExchange exchange) {
        User user = exchange.getAttachment(AuthReader.USER);

        if (user == null) {
            exchange.setStatusCode(StatusCodes.BAD_REQUEST);
            exchange.endExchange();
            return;
        }

        if (user.isBanned()) {
            exchange.setStatusCode(StatusCodes.BAD_REQUEST);
            exchange.endExchange();
            return;
        }

        FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);

        FormData.FormValue xq;
        FormData.FormValue yq;
        FormData.FormValue idq;
        FormData.FormValue msgq;

        try {
            xq = data.getFirst("x");
            yq = data.getFirst("y");
            idq = data.getFirst("id");
            msgq = data.getFirst("message");
        } catch (NullPointerException ex) {
            exchange.setStatusCode(StatusCodes.BAD_REQUEST);
            exchange.endExchange();
            return;
        }

        if (xq == null || yq == null || idq == null || msgq == null) {
            exchange.setStatusCode(StatusCodes.BAD_REQUEST);
            exchange.endExchange();
            return;
        }
        int x = (int) Math.floor(Float.parseFloat(xq.getValue()));
        int y = (int) Math.floor(Float.parseFloat(yq.getValue()));
        int id = Integer.parseInt(idq.getValue());
        if (x < 0 || x >= App.getWidth() || y < 0 || y >= App.getHeight()) {
            exchange.setStatusCode(StatusCodes.BAD_REQUEST);
            exchange.endExchange();
            return;
        }
        DBPixelPlacementFull pxl = App.getDatabase().getPixelByID(null, id);
        if (pxl.x != x || pxl.y != y) {
            exchange.setStatusCode(StatusCodes.BAD_REQUEST);
            exchange.endExchange();
            return;
        }
        Integer rid = App.getDatabase().insertReport(user.getId(), pxl.userId, id, x, y, msgq.getValue());
        if (rid != null)
            App.getServer().broadcastToStaff(new ServerReceivedReport(rid, ServerReceivedReport.REPORT_TYPE_CANVAS));
        exchange.setStatusCode(200);
    }

    public void users(HttpServerExchange exchange) {
        exchange.getResponseHeaders()
                .put(Headers.CONTENT_TYPE, "application/json")
                .put(HttpString.tryFromString("Access-Control-Allow-Origin"), "*");
        exchange.getResponseSender().send(App.getGson().toJson(new ServerUsers(App.getServer().getNonIdledUsersCount())));
    }

    public void whoami(HttpServerExchange exchange) {
        exchange.getResponseHeaders()
                .put(Headers.CONTENT_TYPE, "application/json")
                .put(HttpString.tryFromString("Access-Control-Allow-Credentials"), "true");

        String origin = exchange.getRequestHeaders().getFirst(HttpString.tryFromString("Origin"));
        if (origin != null && App.getWhoamiAllowedOrigins().stream().anyMatch(Predicate.isEqual(origin))) {
            exchange.getResponseHeaders()
                    .put(HttpString.tryFromString("Access-Control-Allow-Origin"), origin);
        }

        User user = exchange.getAttachment(AuthReader.USER);
        if (user != null) {
            exchange.getResponseSender().send(App.getGson().toJson(new WhoAmI(user.getName(), user.getId())));
        } else {
            exchange.getResponseSender().send(App.getGson().toJson(new WhoAmI("unauthed", -1)));
        }
    }

    private User parseUserFromForm(HttpServerExchange exchange) {
        FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);
        if (data != null) {
            FormData.FormValue username = data.getFirst("username");
            if (username != null) {
                return App.getUserManager().getByName(username.getValue());
            }
        }
        return null;
    }

    private void respond(HttpServerExchange exchange, int code, Object obj) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.setStatusCode(code);
        exchange.getResponseSender().send(App.getGson().toJson(obj));
        exchange.endExchange();
    }

    private void redirect(HttpServerExchange exchange, String url) {
        exchange.setStatusCode(StatusCodes.FOUND);
        exchange.getResponseHeaders().put(Headers.LOCATION, url);
        exchange.getResponseSender().send("");
    }

    private String encodedURIComponent(String toEncode) {
        //https://stackoverflow.com/a/611117
        String result = "";
        try {
            result = URLEncoder.encode(toEncode, "UTF-8")
                    .replaceAll("\\+", "%20")
                    .replaceAll("\\%21", "!")
                    .replaceAll("\\%27", "'")
                    .replaceAll("\\%28", "(")
                    .replaceAll("\\%29", ")")
                    .replaceAll("\\%7E", "~");
        } catch (UnsupportedEncodingException e) {
            result = toEncode;
        }
        return result;
    }

    private boolean validateUsername(String username) {
        return !username.isEmpty() && username.matches("[a-zA-Z0-9_\\-]+");
    }

    public void reloadServicesEnabledState() {
        for (Map.Entry<String, AuthService> entry : services.entrySet()) {
            entry.getValue().reloadEnabledState();
        }
    }
}
