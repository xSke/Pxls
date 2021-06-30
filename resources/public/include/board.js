const { lookup } = require('./lookup');
const { place } = require('./place');
const { settings } = require('./settings');
const { template } = require('./template');
const { panels } = require('./panels');
const { user } = require('./user');
const { uiHelper } = require('./uiHelper');
const { chat } = require('./chat');
const { overlays } = require('./overlays');
const { socket } = require('./socket');
const { grid } = require('./grid');
const { ls } = require('./storage');
const { coords } = require('./coords');
let query;

const { flags, createImageData, binaryAjax } = require('./helpers');
const { haveImageRendering, haveZoomRendering } = flags;

// this object holds all board information and is responsible of rendering the board
const board = (function() {
  const self = {
    elements: {
      board: $('#board'),
      board_render: null, // populated on init based on rendering method
      mover: $('#board-mover'),
      zoomer: $('#board-zoomer'),
      container: $('#board-container')
    },
    ctx: null,
    use_js_render: !haveImageRendering && !haveZoomRendering,
    use_zoom: !haveImageRendering && haveZoomRendering,
    width: 0,
    height: 0,
    scale: 1,
    id: null,
    intView: null,
    pan: {
      x: 0,
      y: 0
    },
    allowDrag: true,
    pannedWithKeys: false,
    rgbPalette: [],
    loaded: false,
    pixelBuffer: [],
    holdTimer: {
      id: -1,
      holdTimeout: 500,
      handler: function(args) { // self.holdTimer.handler
        self.holdTimer.id = -1;
        lookup.runLookup(args.x, args.y);
      }
    },
    webInfo: false,
    updateViewport: function(data) {
      if (!isNaN(data.scale)) {
        self.setScale(parseFloat(data.scale), false);
      }
      self.centerOn(data.x, data.y);
    },
    centerOn: function(x, y, ignoreLock = false) {
      if (x != null) self.pan.x = (self.width / 2 - x);
      if (y != null) self.pan.y = (self.height / 2 - y);
      self.update(null, ignoreLock);
    },
    replayBuffer: function() {
      $.map(self.pixelBuffer, function(p) {
        self.setPixelIndex(p.x, p.y, p.c, false);
      });
      self.refresh();
      self.pixelBuffer = [];
    },
    draw: function(data) {
      self.id = createImageData(self.width, self.height);
      self.ctx.mozImageSmoothingEnabled = self.ctx.webkitImageSmoothingEnabled = self.ctx.msImageSmoothingEnabled = self.ctx.imageSmoothingEnabled = false;

      self.intView = new Uint32Array(self.id.data.buffer);
      self.rgbPalette = place.getPaletteABGR();

      for (let i = 0; i < self.width * self.height; i++) {
        self._setPixelIndex(i, data[i]);
      }

      self.ctx.putImageData(self.id, 0, 0);
      self.update();
      self.loaded = true;
      self.replayBuffer();
    },
    initInteraction: function() {
      // first zooming and stuff
      const handleMove = function(evt) {
        if (!self.allowDrag) return;
        self.pan.x += evt.dx / self.scale;
        self.pan.y += evt.dy / self.scale;

        self.update();
      };

      interact(self.elements.container[0]).draggable({
        inertia: true,
        onmove: handleMove
      }).gesturable({
        onmove: function(evt) {
          if (self.allowDrag) {
            self.scale *= (1 + evt.ds);
          }
          handleMove(evt);
        }
      });

      $(document.body).on('keydown', function(evt) {
        if (['INPUT', 'TEXTAREA'].includes(evt.target.nodeName)) {
          // prevent inputs from triggering shortcuts
          return;
        }

        switch (evt.originalEvent.code || evt.keyCode || evt.which || evt.key) {
          case 'KeyW': // W
          case 'ArrowUp':
          case 38: // Arrow Up
          case 87: // W
          case 'w':
          case 'W':
            self.pan.y += 100 / self.scale;
            break;
          case 'KeyD': // D
          case 'ArrowRight':
          case 39: // Arrow Right
          case 68: // D
          case 'd':
          case 'D':
            self.pan.x -= 100 / self.scale;
            break;
          case 'KeyS': // S
          case 'ArrowDown':
          case 40: // Arrow Down
          case 83: // S
          case 's':
          case 'S':
            self.pan.y -= 100 / self.scale;
            break;
          case 'KeyA': // A
          case 'ArrowLeft':
          case 37: // Arrow Left
          case 65: // A
          case 'a':
          case 'A':
            self.pan.x += 100 / self.scale;
            break;
          case 'KeyP':
          case 80: // P
          case 'p':
          case 'P':
            self.save();
            break;
          case 'KeyL':
          case 76: // L
          case 'l':
          case 'L':
            settings.board.lock.enable.toggle();
            break;
          case 'KeyR':
          case 82: // R
          case 'r':
          case 'R': {
            const tempOpts = template.getOptions();
            if (tempOpts.use) {
              self.centerOn(tempOpts.x + (template.getDisplayWidth() / 2), tempOpts.y + (template.getDisplayHeight() / 2));
            } else if (place.lastPixel) {
              self.centerOn(place.lastPixel.x, place.lastPixel.y);
            }
            break;
          }
          case 'KeyJ':
          case 74: // J
          case 'j':
          case 'J':
            if (place.color < 1) {
              place.switch(place.palette.length - 1);
            } else {
              place.switch(place.color - 1);
            }
            break;
          case 'KeyK':
          case 75: // K
          case 'k':
          case 'K':
            if (place.color + 1 >= place.palette.length) {
              place.switch(0);
            } else {
              place.switch(place.color + 1);
            }
            break;
            // Following stuff could be broken af for many non-English layouts
          case 'KeyE': // E
          case 'Equal': // =
          case 'NumpadAdd': // numpad +
          case 69: // E
          case 107: // numpad +
          case 187: // =
          case 171: // +
          case '=':
          case 'e':
          case 'E':
            self.nudgeScale(1);
            break;
          case 'KeyQ': // Q
          case 'Minus': // -
          case 'NumpadSubtract': // numpad -
          case 81: // Q
          case 109: // numpad -
          case 173: // -
          case 189: // -
          case 'q':
          case 'Q':
          case '-':
            self.nudgeScale(-1);
            break;
          case 't':
          case 'T':
          case 'KeyT':
          case 84: // t
            panels.toggle('settings');
            break;
          case 'i':
          case 'I':
          case 'KeyI':
          case 73: // i
            panels.toggle('info');
            break;
          case 'b':
          case 'B':
          case 'KeyB':
          case 66: // b
            panels.toggle('chat');
            break;
        }
        self.pannedWithKeys = true;
        self.update();
      });

      self.elements.container[0].addEventListener('wheel', function(evt) {
        if (!self.allowDrag) return;
        const oldScale = self.getScale();

        let delta = -evt.deltaY;

        switch (evt.deltaMode) {
          case WheelEvent.DOM_DELTA_PIXEL:
            // 53 pixels is the default chrome gives for a wheel scroll.
            delta /= 53;
            break;
          case WheelEvent.DOM_DELTA_LINE:
            // default case on Firefox, three lines is default number.
            delta /= 3;
            break;
          case WheelEvent.DOM_DELTA_PAGE:
            delta = Math.sign(delta);
            break;
        }

        self.nudgeScale(delta);

        const scale = self.getScale();
        if (oldScale !== scale) {
          const dx = evt.clientX - self.elements.container.width() / 2;
          const dy = evt.clientY - self.elements.container.height() / 2;
          self.pan.x -= dx / oldScale;
          self.pan.x += dx / scale;
          self.pan.y -= dy / oldScale;
          self.pan.y += dy / scale;
          self.update();
          place.update();
        }
      }, { passive: true });

      // now init the movement
      let downX, downY, downStart;
      self.elements.board_render.on('pointerdown mousedown', handleInputDown)
        .on('pointermove mousemove', handleInputMove)
        .on('pointerup mouseup touchend', handleInputUp)
        .contextmenu(function(evt) {
          evt.preventDefault();
          switch (settings.place.rightclick.action.get()) {
            case 'clear':
              place.switch(-1);
              break;
            case 'copy': {
              const coordinates = board.fromScreen(evt.clientX, evt.clientY, true);
              const index = board.getPixelIndex(coordinates.x, coordinates.y);
              place.switch(index);
              break;
            }
            case 'lookup':
              lookup.runLookup(evt.clientX, evt.clientY);
              break;
            case 'clearlookup':
              place.switch(-1);
              lookup.runLookup(evt.clientX, evt.clientY);
              break;
          }
        });

      // Separated some of these events from jQuery to deal with chrome's complaints about passive event violations.
      self.elements.board_render[0].addEventListener('touchstart', handleInputDown, { passive: false });
      self.elements.board_render[0].addEventListener('touchmove', handleInputMove, { passive: false });

      function handleInputDown(event) {
        if (['INPUT', 'TEXTAREA'].includes(document.activeElement.nodeName)) {
          document.activeElement.blur();
        }

        let clientX = 0;
        let clientY = 0;
        let prereq = true;
        if (event.changedTouches && event.changedTouches[0]) {
          clientX = event.changedTouches[0].clientX;
          clientY = event.changedTouches[0].clientY;
        } else {
          clientX = event.clientX;
          clientY = event.clientY;
          if (event.button != null) prereq = event.button === 0; // if there are buttons, is the the left mouse button?
        }
        downX = clientX;
        downY = clientY;
        if (prereq && self.holdTimer.id === -1) {
          self.holdTimer.id = setTimeout(self.holdTimer.handler, self.holdTimer.holdTimeout, {
            x: clientX,
            y: clientY
          });
        }
        downStart = Date.now();
      }

      function handleInputMove(event) {
        if (self.holdTimer.id === -1) return;
        let clientX = -1;
        let clientY = -1;

        if (event.changedTouches && event.changedTouches[0]) {
          clientX = event.changedTouches[0].clientX;
          clientY = event.changedTouches[0].clientY;
        } else {
          clientX = event.clientX;
          clientY = event.clientY;
        }
        if (Math.abs(downX - clientX) > 5 || Math.abs(downY - clientY) > 5) {
          clearTimeout(self.holdTimer.id);
          self.holdTimer.id = -1;
        }
      }

      function handleInputUp(event) {
        if (self.holdTimer.id !== -1) {
          clearTimeout(self.holdTimer.id);
        }
        if (event.shiftKey === true) return;
        self.holdTimer.id = -1;
        let touch = false;
        let clientX = event.clientX;
        let clientY = event.clientY;
        let downDelta = Date.now() - downStart;
        if (event.type === 'touchend') {
          touch = true;
          clientX = event.changedTouches[0].clientX;
          clientY = event.changedTouches[0].clientY;
        }
        const dx = Math.abs(downX - clientX);
        const dy = Math.abs(downY - clientY);
        if ((event.button === 0 || touch) && downDelta < 500) {
          let pos;
          if (!self.allowDrag && dx < 25 && dy < 25) {
            pos = self.fromScreen(downX, downY);
            place.place(pos.x, pos.y);
          } else if (dx < 5 && dy < 5) {
            pos = self.fromScreen(clientX, clientY);
            place.place(pos.x, pos.y);
          }
        }
        downDelta = 0;
        if (event.button != null) {
          // Is the button pressed the middle mouse button?
          if (settings.place.picker.enable.get() === true && event.button === 1 && dx < 15 && dy < 15) {
            // If so, switch to the color at the location.
            const { x, y } = self.fromScreen(event.clientX, event.clientY);
            place.switch(self.getPixelIndex(x, y));
          }
        }
      }
    },
    init: function() {
      query = require('./query').query;

      $(window).on('pxls:queryUpdated', (evt, propName, oldValue, newValue) => {
        const nullish = newValue == null;
        switch (propName.toLowerCase()) {
          case 'x':
          case 'y':
            board.centerOn(query.get('x') >> 0, query.get('y') >> 0);
            break;
          case 'scale':
            board.setScale(newValue >> 0, true);
            break;
          case 'template':
            template.queueUpdate({ template: newValue, use: newValue !== null });
            break;
          case 'ox':
            template.queueUpdate({ ox: nullish ? null : newValue >> 0 });
            break;
          case 'oy':
            template.queueUpdate({ oy: nullish ? null : newValue >> 0 });
            break;
          case 'tw':
            template.queueUpdate({ tw: nullish ? null : newValue >> 0 });
            break;
          case 'title':
            template.queueUpdate({ title: nullish ? '' : newValue });
            break;
          case 'oo': {
            let parsed = parseFloat(newValue);
            if (!Number.isFinite(parsed)) parsed = null;
            template.queueUpdate({ oo: parsed == null ? null : parsed });
            break;
          }
          case 'convert':
            template.queueUpdate({ convert: newValue });
            break;
        }
      });
      $('#ui').hide();
      self.elements.container.hide();

      if (self.use_js_render) {
        self.elements.board_render = $('<canvas>').css({
          width: '100vw',
          height: '100vh',
          margin: 0,
          marginTop: 3 // wtf? Noticed by experimenting
        });
        self.elements.board.parent().append(self.elements.board_render);
        self.elements.board.detach();
      } else {
        self.elements.board_render = self.elements.board;
      }
      self.ctx = self.elements.board[0].getContext('2d');
      self.initInteraction();

      settings.board.template.beneathoverlays.listen(function(value) {
        self.elements.container.toggleClass('lower-template', value);
      });
    },
    start: function() {
      const { chromeOffsetWorkaround } = require('./chromeOffsetWorkaround');

      $.get('/info', async (data) => {
        overlays.webinit(data);
        user.webinit(data);
        self.width = data.width;
        self.height = data.height;
        place.setPalette(data.palette);
        template.webinit(data);
        uiHelper.setMax(data.maxStacked);
        chat.webinit(data);
        uiHelper.initBanner(data.chatBannerText);
        chromeOffsetWorkaround.update();
        self.webInfo = data;
        if (data.captchaKey) {
          $('.g-recaptcha').attr('data-sitekey', data.captchaKey);

          $.getScript('https://www.google.com/recaptcha/api.js');
        }
        self.elements.board.attr({
          width: self.width,
          height: self.height
        });

        const cx = query.get('x') || self.width / 2;
        const cy = query.get('y') || self.height / 2;
        self.setScale(query.get('scale') || self.scale, false);
        self.centerOn(cx, cy, true);
        socket.init();

        if (self.use_js_render) {
          $(window).resize(function() {
            self.update();
          }).resize();
        } else {
          $(window).resize(function() {
            place.update();
            grid.update();
          });
        }
        const url = query.get('template');
        if (url) { // we have a template!
          template.queueUpdate({
            use: true,
            x: parseFloat(query.get('ox')),
            y: parseFloat(query.get('oy')),
            opacity: parseFloat(query.get('oo')),
            width: parseFloat(query.get('tw')),
            title: query.get('title'),
            url: url,
            direct: query.get('direct'),
            convertMode: query.get('convert')
          });
        }
        let spin = parseFloat(query.get('spin'));
        if (spin) { // SPIN SPIN SPIN!!!!
          spin = 360 / (spin * 1000);
          let degree = 0;
          let start = null;
          const spiiiiiin = function(timestamp) {
            if (!start) {
              start = timestamp;
            }
            const delta = (timestamp - start);
            degree += spin * delta;
            degree %= 360;
            start = timestamp;
            self.elements.container.css('transform', 'rotate(' + degree + 'deg)');
            window.requestAnimationFrame(spiiiiiin);
          };
          window.requestAnimationFrame(spiiiiiin);
        }
        const color = ls.get('color');
        if (color != null) {
          place.switch(parseInt(color));
        }

        settings.board.zoom.rounding.enable.listen(function(value) {
          // this rounds the current scale if it needs rounding
          self.setScale(self.getScale());
        });

        try {
          self.draw(await binaryAjax('/boarddata' + '?_' + (new Date()).getTime()));
        } catch (e) {
          console.error('Error drawing board:', e);
          socket.reconnect();
        }
      }).fail(function(e) {
        console.error('Error fetching /info:', e);
        socket.reconnect();
      });
    },
    update: function(optional, ignoreCanvasLock = false) {
      const scale = self.getScale();
      if (self.loaded) {
        self.pan.x = Math.min(self.width / 2, Math.max(-self.width / 2, self.pan.x));
        self.pan.y = Math.min(self.height / 2, Math.max(-self.height / 2, self.pan.y));
        query.set({
          x: Math.round((self.width / 2) - self.pan.x),
          y: Math.round((self.height / 2) - self.pan.y),
          scale: Math.round(scale * 100) / 100
        }, true);
      }
      if (self.use_js_render) {
        const ctx2 = self.elements.board_render[0].getContext('2d');
        let pxlX = -self.pan.x + ((self.width - (window.innerWidth / scale)) / 2);
        let pxlY = -self.pan.y + ((self.height - (window.innerHeight / scale)) / 2);
        let dx = 0;
        let dy = 0;
        let dw = 0;
        let dh = 0;
        let pxlW = window.innerWidth / scale;
        let pxlH = window.innerHeight / scale;

        if (pxlX < 0) {
          dx = -pxlX;
          pxlX = 0;
          pxlW -= dx;
          dw += dx;
        }

        if (pxlY < 0) {
          dy = -pxlY;
          pxlY = 0;
          pxlH -= dy;
          dh += dy;
        }

        if (pxlX + pxlW > self.width) {
          dw += pxlW + pxlX - self.width;
          pxlW = self.width - pxlX;
        }

        if (pxlY + pxlH > self.height) {
          dh += pxlH + pxlY - self.height;
          pxlH = self.height - pxlY;
        }

        ctx2.canvas.width = window.innerWidth;
        ctx2.canvas.height = window.innerHeight;
        ctx2.mozImageSmoothingEnabled = ctx2.webkitImageSmoothingEnabled = ctx2.msImageSmoothingEnabled = ctx2.imageSmoothingEnabled = (scale < 1);

        ctx2.globalAlpha = 1;
        ctx2.fillStyle = '#CCCCCC';
        ctx2.fillRect(0, 0, ctx2.canvas.width, ctx2.canvas.height);
        ctx2.drawImage(self.elements.board[0],
          pxlX,
          pxlY,
          pxlW,
          pxlH,
          0 + (dx * scale),
          0 + (dy * scale),
          window.innerWidth - (dw * scale),
          window.innerHeight - (dh * scale)
        );

        template.draw(ctx2, pxlX, pxlY);

        place.update();
        grid.update();
        return true;
      }
      if (optional) {
        return false;
      }

      self.elements.board.toggleClass('pixelate', scale > 1);
      overlays.heatmap.setPixelated(scale >= 1);
      overlays.heatbackground.setPixelated(scale >= 1);
      overlays.virginmap.setPixelated(scale >= 1);
      overlays.virginbackground.setPixelated(scale >= 1);
      template.setPixelated(scale >= template.getWidthRatio());

      if (ignoreCanvasLock || self.allowDrag || (!self.allowDrag && self.pannedWithKeys)) {
        self.elements.mover.css({
          width: self.width,
          height: self.height,
          transform: 'translate(' + (scale <= 1 ? Math.round(self.pan.x) : self.pan.x) + 'px, ' + (scale <= 1 ? Math.round(self.pan.y) : self.pan.y) + 'px)'
        });
      }
      if (self.use_zoom) {
        self.elements.zoomer.css('zoom', (scale * 100).toString() + '%');
      } else {
        self.elements.zoomer.css('transform', 'scale(' + scale + ')');
      }

      place.update();
      grid.update();
      return true;
    },
    getScale: function() {
      return Math.abs(self.scale);
    },
    setScale: function(scale, doUpdate = true) {
      // TODO: Determine why these values are being returned as strings rather than their appropriate type (float)
      const minimum = parseFloat(settings.board.zoom.limit.minimum.get());
      const maximum = parseFloat(settings.board.zoom.limit.maximum.get());
      // TODO: Determine why scale is a string
      scale = parseFloat(scale);
      if (scale > maximum) {
        scale = maximum;
      } else if (scale <= minimum) {
        // enforce the [x, y] limit without blindly resetting to x when the user was trying to zoom in farther than y
        scale = minimum;
      }

      if (settings.board.zoom.rounding.enable.get()) {
        // We round up if zooming in and round down if zooming out to ensure that the level does change
        // even if the scrolled amount wouldn't be closer to the next rounded value than the last one.
        let round;
        if (scale < self.scale) {
          round = Math.floor;
        } else {
          round = Math.ceil;
        }

        if (scale > 1) {
          if (self.scale < 1) {
            scale = 1;
          } else {
            scale = round(scale);
          }
        } else {
          if (self.scale > 1) {
            scale = 1;
          } else {
            scale = 2 ** round(Math.log(scale) / Math.log(2));
          }
        }
      }

      self.scale = scale;
      if (doUpdate) {
        self.update();
      }
    },
    getZoomBase: function() {
      return parseFloat(settings.board.zoom.sensitivity.get()) || 1.5;
    },
    nudgeScale: function(adj) {
      const zoomBase = self.getZoomBase();

      self.setScale(self.scale * zoomBase ** adj);
    },
    getPixelIndex: function(x, y) {
      x = Math.floor(x);
      y = Math.floor(y);
      if (!self.loaded) {
        return self.pixelBuffer.findIndex((pix) => pix.x === x && pix.y === y);
      }
      const colorValue = self.intView[y * self.width + x];
      const index = self.rgbPalette.indexOf(colorValue);
      return index !== -1 ? index : 0xFF;
    },
    _setPixelIndex: function(i, c) {
      if (c === -1 || c === 0xFF) {
        // transparent.
        self.intView[i] = 0x00000000;
      } else {
        self.intView[i] = self.rgbPalette[c];
      }
    },
    setPixelIndex: function(x, y, c, refresh) {
      if (!self.loaded) {
        self.pixelBuffer.push({
          x: x,
          y: y,
          c: c
        });
        return;
      }
      if (refresh === undefined) {
        refresh = true;
      }
      self._setPixelIndex(y * self.width + x, c);
      if (refresh) {
        self.ctx.putImageData(self.id, 0, 0);
      }
    },
    refresh: function() {
      if (self.loaded) {
        self.ctx.putImageData(self.id, 0, 0);
      }
    },
    fromScreen: function(screenX, screenY, floored = true) {
      let toRet = { x: 0, y: 0 };
      let adjustX = 0;
      let adjustY = 0;
      if (self.scale < 0) {
        adjustX = self.width;
        adjustY = self.height;
      }

      const scale = self.getScale();
      if (self.use_js_render) {
        toRet = {
          x: -self.pan.x + ((self.width - (window.innerWidth / scale)) / 2) + (screenX / scale) + adjustX,
          y: -self.pan.y + ((self.height - (window.innerHeight / scale)) / 2) + (screenY / scale) + adjustY
        };
      } else {
        // we scope these into the `else` so that we don't have to redefine `boardBox` twice. getBoundingClientRect() forces a redraw so we don't want to do it every call either if we can help it.
        const boardBox = self.elements.board[0].getBoundingClientRect();
        if (self.use_zoom) {
          toRet = {
            x: (screenX / scale) - boardBox.left + adjustX,
            y: (screenY / scale) - boardBox.top + adjustY
          };
        } else {
          toRet = {
            x: ((screenX - boardBox.left) / scale) + adjustX,
            y: ((screenY - boardBox.top) / scale) + adjustY
          };
        }
      }

      if (floored) {
        toRet.x >>= 0;
        toRet.y >>= 0;
      }

      return toRet;
    },
    toScreen: function(boardX, boardY) {
      const scale = self.getScale();
      if (scale < 0) {
        boardX -= self.width - 1;
        boardY -= self.height - 1;
      }
      if (self.use_js_render) {
        return {
          x: (boardX + self.pan.x - ((self.width - (window.innerWidth / scale)) / 2)) * scale,
          y: (boardY + self.pan.y - ((self.height - (window.innerHeight / scale)) / 2)) * scale
        };
      }
      const boardBox = self.elements.board[0].getBoundingClientRect();
      if (self.use_zoom) {
        return {
          x: (boardX + boardBox.left) * scale,
          y: (boardY + boardBox.top) * scale
        };
      }
      return {
        x: boardX * scale + boardBox.left,
        y: boardY * scale + boardBox.top
      };
    },
    save: function() {
      const a = document.createElement('a');
      const format = settings.board.snapshot.format.get();

      a.href = self.elements.board[0].toDataURL(format, 1);
      a.download = (new Date()).toISOString().replace(/^(\d+-\d+-\d+)T(\d+):(\d+):(\d).*$/, `pxls canvas $1 $2.$3.$4.${format.split('/')[1]}`);
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      if (typeof a.remove === 'function') {
        a.remove();
      }
    },
    getRenderBoard: function() {
      return self.elements.board_render;
    },
    validateCoordinates: (x, y) => {
      return (x >= 0 && x <= self.width) && (y >= 0 && y <= self.height);
    }
  };
  return {
    init: self.init,
    start: self.start,
    update: self.update,
    getScale: self.getScale,
    nudgeScale: self.nudgeScale,
    setScale: self.setScale,
    getPixelIndex: self.getPixelIndex,
    setPixelIndex: self.setPixelIndex,
    fromScreen: self.fromScreen,
    toScreen: self.toScreen,
    save: self.save,
    centerOn: self.centerOn,
    getRenderBoard: self.getRenderBoard,
    getContainer: () => self.elements.container,
    getWidth: () => self.width,
    getHeight: () => self.height,
    refresh: self.refresh,
    updateViewport: self.updateViewport,
    get allowDrag() {
      return self.allowDrag;
    },
    setAllowDrag: (allowDrag) => {
      self.allowDrag = allowDrag === true;
      if (self.allowDrag) { coords.lockIcon.fadeOut(200); } else { coords.lockIcon.fadeIn(200); }
    },
    validateCoordinates: self.validateCoordinates,
    get webInfo() {
      return self.webInfo;
    },
    get snipMode() {
      return self.webInfo && self.webInfo.snipMode === true;
    }
  };
})();

module.exports.board = board;
