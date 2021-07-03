const { settings } = require('./settings');
const { nativeNotifications } = require('./nativeNotifications');
const { uiHelper } = require('./uiHelper');
const { socket } = require('./socket');

// this takes care of the countdown timer
module.exports.timer = (function() {
  const self = {
    elements: {
      palette: $('#palette'),
      timer_container: $('#cooldown'),
      timer_countdown: $('#cooldown-timer'),
      timer_chat: $('#txtMobileChatCooldown')
    },
    hasCooledDown: false,
    hasFiredNotification: true,
    cooldown: 0,
    runningTimer: false,
    audio: new Audio('notify.wav'),
    title: '',
    cooledDown: function() {
      return self.hasCooledDown;
    },
    update: function(die) {
      self.hasCooledDown = false;
      // subtract one extra millisecond to prevent the first displaying to be derped
      let delta = (self.cooldown - (new Date()).getTime() - 1) / 1000;

      if (self.runningTimer === false) {
        self.elements.timer_container.hide();
      }

      if (self.status) {
        self.elements.timer_countdown.text(self.status);
      }

      const alertDelay = settings.place.alert.delay.get();
      if (alertDelay < 0 && delta < Math.abs(alertDelay) && !self.hasFiredNotification) {
        self.playAudio();
        let notif;
        if (!document.hasFocus()) {
          notif = nativeNotifications.maybeShow(`Your next pixel will be available in ${Math.abs(alertDelay)} seconds!`);
        }
        setTimeout(() => {
          self.hasCooledDown = true;
          uiHelper.setPlaceableText(1);
          if (notif) {
            $(window).one('pxls:ack:place', () => notif.close());
          }
        }, delta * 1000);
        self.hasFiredNotification = true;
      }

      if (delta > 0) {
        self.elements.timer_container.show();
        delta++; // real people don't count seconds zero-based (programming is more awesome)
        const secs = Math.floor(delta % 60);
        const secsStr = secs < 10 ? '0' + secs : secs;
        const minutes = Math.floor(delta / 60);
        const minuteStr = minutes < 10 ? '0' + minutes : minutes;
        self.elements.timer_countdown.text(`${minuteStr}:${secsStr}`);
        self.elements.timer_chat.text(`(${minuteStr}:${secsStr})`);

        document.title = uiHelper.getTitle(`[${minuteStr}:${secsStr}]`);

        if (self.runningTimer && !die) {
          return;
        }
        self.runningTimer = true;
        setTimeout(function() {
          self.update(true);
        }, 1000);
        return;
      }

      self.runningTimer = false;

      document.title = uiHelper.getTitle();
      self.elements.timer_container.hide();
      self.elements.timer_chat.text('');

      if (alertDelay > 0) {
        setTimeout(() => {
          self.playAudio();
          if (!document.hasFocus()) {
            const notif = nativeNotifications.maybeShow(`Your next pixel has been available for ${alertDelay} seconds!`);
            if (notif) {
              $(window).one('pxls:ack:place', () => notif.close());
            }
          }
          self.hasCooledDown = true;
          uiHelper.setPlaceableText(1);
          self.hasFiredNotification = true;
        }, alertDelay * 1000);
        return;
      }

      if (!self.hasFiredNotification) {
        self.playAudio();
        if (!document.hasFocus()) {
          const notif = nativeNotifications.maybeShow('Your next pixel is available!');
          if (notif) {
            $(window).one('pxls:ack:place', () => notif.close());
          }
        }
        self.hasCooledDown = true;
        uiHelper.setPlaceableText(1);
        self.hasFiredNotification = true;
      }
    },
    init: function() {
      self.title = document.title;
      self.elements.timer_container.hide();
      self.elements.timer_chat.text('');

      setTimeout(function() {
        if (self.cooldown < (new Date()).getTime() && uiHelper.getAvailable() === 0) {
          self.hasCooledDown = true;
          uiHelper.setPlaceableText(1);
        }
      }, 250);
      socket.on('cooldown', function(data) {
        self.cooldown = (new Date()).getTime() + (data.wait * 1000);
        self.hasFiredNotification = data.wait === 0;
        self.update();
      });
    },
    playAudio: function() {
      if (uiHelper.tabHasFocus() && settings.audio.enable.get()) {
        self.audio.play();
      }
    }
  };
  return {
    init: self.init,
    cooledDown: self.cooledDown,
    playAudio: self.playAudio,
    audioElem: self.audio
  };
})();
