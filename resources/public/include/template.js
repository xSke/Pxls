let board;
let query;
let uiHelper;

// here all the template stuff happens
module.exports.template = (function() {
  const STYLES_Y = 16;
  const STYLES_X = 16;
  const self = {
    elements: {
      visibles: null, // set to a collection of template and sourceImage in init
      template: $('<canvas>'),
      sourceImage: $('<img>').attr({ crossOrigin: '' }),
      styleImage: $('<img>'),
      useCheckbox: $('#template-use'),
      titleInput: $('#template-title'),
      urlInput: $('#template-url'),
      imageErrorWarning: $('#template-image-error-warning'),
      coordsXInput: $('#template-coords-x'),
      coordsYInput: $('#template-coords-y'),
      widthInput: $('#template-width'),
      widthResetBtn: $('#template-width-reset'),
      styleSelect: $('#template-style-mode'),
      styleOptionCustom: $('#template-style-mode-custom'),
      customStyleInput: $('#template-custom-style-url'),
      conversionModeSelect: $('#template-conversion-mode-select'),
      opacityInput: $('#template-opacity'),
      opacityPercentage: $('#template-opacity-percentage')
    },
    gl: {
      context: null,
      textures: {
        source: null,
        // this is used as the color attachment of the intermediate framebuffer
        downscaled: null,
        style: null
      },
      framebuffers: {
        // this null is final - it's how the default framebuffer is specified in WebGL
        main: null,
        // this is the buffer into which a 1-to-1 template image is drawn.
        intermediate: null
      },
      buffers: {
        vertex: null
      },
      programs: {
        downscaling: {
          unconverted: null,
          nearestCustom: null
        },
        stylize: null
      }
    },
    corsProxy: {
      base: undefined,
      param: null,
      // a list of sites that will already serve cors-enabled content
      safeHosts: [
        'imgur.com',
        'pxlsfiddle.com',
        window.location.host
      ]
    },
    queueTimer: 0,
    loading: false,
    _queuedUpdates: {},
    _defaults: {
      url: '',
      x: 0,
      y: 0,
      width: -1,
      opacity: 0.5,
      title: '',
      convertMode: 'unconverted',
      style: undefined
    },
    options: {},
    initCORS: function(base, param) {
      self.corsProxy.base = base;
      self.corsProxy.param = param;

      self.loadImage();
    },
    cors: function(location) {
      try {
        const url = new URL(location);
        if (self.corsProxy.safeHosts.some(h => url.hostname.endsWith(h))) {
          return url.href;
        } else {
          if (self.corsProxy.param) {
            return `${self.corsProxy.base}?${self.corsProxy.param}=${encodeURIComponent(url.href)}`;
          } else {
            return `${self.corsProxy.base}/${url.href}`;
          }
        }
      } catch (e) {
        // invalid URLs fail silently.
        return location;
      }
    },
    loadImage: function() {
      if (self.corsProxy.base !== undefined) {
        self.loading = true;

        self.options.use = true;

        self.elements.imageErrorWarning.empty();
        self.elements.imageErrorWarning.hide();

        self.elements.sourceImage.attr({
          src: self.cors(self.options.url)
        });
      }
    },
    updateSettings: function() {
      self.elements.useCheckbox.prop('checked', self.options.use);
      self.elements.urlInput.val(self.options.url ? self.options.url : '');

      self.elements.titleInput
        .prop('disabled', !self.options.use)
        .val(self.options.title ? self.options.title : '');

      self.elements.opacityInput
        .prop('disabled', !self.options.use)
        .val(self.options.opacity);
      self.elements.opacityPercentage.text(`${Math.floor(self.options.opacity * 100)}%`);

      self.elements.coordsXInput
        .prop('disabled', !self.options.use)
        .val(self.options.x);
      self.elements.coordsYInput
        .prop('disabled', !self.options.use)
        .val(self.options.y);

      self.elements.widthInput.prop('disabled', !self.options.use);
      if (self.options.width >= 0) {
        self.elements.widthInput.val(self.options.width);
      } else if (self.elements.template) {
        self.elements.widthInput.val(self.getSourceWidth());
      } else {
        self.elements.widthInput.val(null);
      }

      self.elements.conversionModeSelect.val(self.options.convertMode);
      const styles = Array.from(self.elements.styleSelect.children('option')).map(o => o.value);
      const style = self.elements.styleImage.attr('src');

      if (!styles.includes(style)) {
        self.elements.customStyleInput.val(style);
      }
      self.elements.styleSelect.val(style);
    },
    normalizeTemplateObj(objectToNormalize, direction) {
      // direction: true = url_to_template_obj, else = template_obj_to_url
      // normalize the given update object with settings that may be present from someone guessing options based on the URL

      const iterOver = [['tw', 'width'], ['ox', 'x'], ['oy', 'y'], ['oo', 'opacity'], ['template', 'url'], ['title', 'title'], ['convert', 'convertMode']];
      if (direction !== true) {
        for (let i = 0; i < iterOver.length; i++) { iterOver[i].reverse(); }
      }

      for (let i = 0; i < iterOver.length; i++) {
        const x = iterOver[i];
        if ((x[0] in objectToNormalize) && objectToNormalize[x[1]] == null) { // if "tw" is set on `objectToNormalize` and `objectToNormalize.width` is not set
          objectToNormalize[x[1]] = objectToNormalize[x[0]]; // set `objectToNormalize["width"]` to `objectToNormalize["tw"]`
          delete objectToNormalize[x[0]]; // and delete `objectToNormalize["tw"]`
        }
      }

      return objectToNormalize;
    },
    queueUpdate: function(obj) {
      obj = self.normalizeTemplateObj(obj, true);
      self._queuedUpdates = Object.assign(self._queuedUpdates, obj);
      if (self.queueTimer) {
        clearTimeout(self.queueTimer);
      }
      self.queueTimer = setTimeout(function() {
        self._update(self._queuedUpdates);
        self._queuedUpdates = {};
        self.queueTimer = 0;
      }, 200);
    },
    _update: function(options, updateSettings = true) {
      if (!Object.keys(options).length) {
        return;
      }

      const urlUpdated = (options.url !== self.options.url && decodeURIComponent(options.url) !== self.options.url && options.url != null);
      if (options.url != null && options.url.length > 0) {
        options.url = decodeURIComponent(options.url);
      }
      if (options.title != null && options.title.length > 0) {
        options.title = decodeURIComponent(options.title);
      }

      // fix for `width` and other props being set after disabling template with the 'v' key then enabling a template without said prop set in the URL.
      if (urlUpdated && !self.options.use) {
        ['width', 'x', 'y', 'opacity', 'convertMode'].forEach(x => {
          if (!Object.prototype.hasOwnProperty.call(options, x)) {
            options[x] = self._defaults[x];
          }
        });
      }

      options = Object.assign({}, self._defaults, self.options, self.normalizeTemplateObj(options, true)); // ensure every option needed to move forward is present
      Object.keys(self._defaults).forEach(x => { // and make sure they're all usable "out of the box"
        if (options[x] == null || (typeof options[x] === 'number' && isNaN(options[x]))) {
          options[x] = self._defaults[x];
        }
      });
      options.opacity = parseFloat(options.opacity.toFixed(2)); // cleans up opacity for the URL, e.g. 1.3877787807814457e-16 => 0

      if (!(options.convertMode in self.gl.programs.downscaling)) {
        options.convertMode = self._defaults.convertMode;
      }

      const newConvertMode = options.convertMode !== self.options.convertMode;

      self.options = options;

      if (options.url.length === 0 || options.use === false) {
        self.options.use = false;
        board.update(true);
        ['template', 'ox', 'oy', 'oo', 'tw', 'title', 'convert'].forEach(x => query.remove(x, true));
      } else {
        self.options.use = true;
        if (urlUpdated === true) {
          self.loadImage();
        }

        if (!self.loading && (self.isDirty() || newConvertMode)) {
          self.rasterizeTemplate();
        }

        [['url', 'template'], ['x', 'ox'], ['y', 'oy'], ['width', 'tw'], ['opacity', 'oo'], ['title', 'title'], ['convertMode', 'convert']].forEach(x => {
          query.set(x[1], self.options[x[0]], true);
        });
      }

      self.elements.styleImage.attr({ src: self.options.style });

      self.applyOptions();

      if (updateSettings) {
        self.updateSettings();
      }
      document.title = uiHelper.getTitle();

      self.setPixelated(query.get('scale') >= self.getWidthRatio());
    },
    usesStyle() {
      return !!self.options.style;
    },
    applyOptions() {
      if (self.options.use) {
        [['left', 'x'], ['top', 'y'], ['opacity', 'opacity']].forEach(x => {
          self.elements.visibles.css(x[0], self.options[x[1]]);
        });
      }

      self.elements.template.toggleClass('hidden', !self.options.use || !self.usesStyle());
      self.elements.sourceImage.toggleClass('hidden', !self.options.use || self.usesStyle());
    },
    updateSize: function() {
      self.elements.visibles.css({
        width: self.getDisplayWidth()
      });
      self.elements.template.attr({
        width: self.getInternalWidth(),
        height: self.getInternalHeight()
      });
    },
    isDirty: function() {
      return parseFloat(self.elements.visibles.css('width')) !== self.getDisplayWidth() ||
        parseFloat(self.elements.visibles.attr('width')) !== self.getInternalWidth() ||
        parseFloat(self.elements.visibles.attr('height')) !== self.getInternalHeight();
    },
    disableTemplate: function() {
      self._update({ url: null });
    },
    draw: function(ctx2, pxlX, pxlY) {
      if (!self.options.use) {
        return;
      }
      let width = self.elements.template[0].width;
      let height = self.elements.template[0].height;
      const scale = board.getScale();
      if (self.options.width !== -1) {
        height *= (self.options.width / width);
        width = self.options.width;
      }
      ctx2.globalAlpha = self.options.opacity;
      ctx2.drawImage(self.elements.template[0], (self.options.x - pxlX) * scale, (self.options.y - pxlY) * scale, width * scale, height * scale);
    },
    init: function() {
      board = require('./board').board;
      query = require('./query').query;
      uiHelper = require('./uiHelper').uiHelper;
      self.elements.visibles = $().add(self.elements.template).add(self.elements.sourceImage).addClass('noselect board-template');

      self.elements.imageErrorWarning.hide();

      self.elements.useCheckbox.change((e) => self._update({ use: e.target.checked }));
      self.elements.titleInput.change((e) => self._update({ title: e.target.value }, false));
      self.elements.urlInput.change((e) => self._update({ use: true, url: e.target.value }));

      self.elements.opacityInput.on('change input', (e) => {
        self.elements.opacityPercentage.text(`${Math.floor(e.target.value * 100)}%`);
        self._update({ opacity: parseFloat(e.target.value) }, false);
      });

      self.elements.coordsXInput.on('change input', (e) => self._update({ x: parseInt(e.target.value) }, false));
      self.elements.coordsYInput.on('change input', (e) => self._update({ y: parseInt(e.target.value) }, false));

      self.elements.widthInput.on('change input', (e) => self._update({ width: parseFloat(e.target.value) }, false));
      self.elements.widthResetBtn.on('click', (e) => self._update({ width: -1 }));

      self.elements.styleSelect.on('change input', (e) => self._update({ style: e.target.value }));
      self.elements.customStyleInput.on('change input', (e) => self.elements.styleOptionCustom.attr({ value: e.target.value }));

      self.elements.conversionModeSelect.on('change input', (e) => self._update({ convertMode: e.target.value }));

      self.updateSettings();

      $(window).keydown(function(evt) {
        if (['INPUT', 'TEXTAREA'].includes(evt.target.nodeName)) {
          // prevent inputs from triggering shortcuts
          return;
        }

        if (self.options.use) {
          switch (evt.originalEvent.code || evt.originalEvent.keyCode || evt.originalEvent.which || evt.originalEvent.key) {
            case 'ControlLeft':
            case 'ControlRight':
            case 'Control':
            case 17:
            case 'AltLeft':
            case 'AltRight':
            case 'Alt':
            case 18:
              evt.preventDefault();
              self.elements.visibles.css('pointer-events', 'initial');
              break;
          }
        }
        let newOpacity = 0;
        switch (evt.code || evt.keyCode || evt.which || evt.key) {
          case 'PageUp':
          case 33:
            newOpacity = Math.min(1, self.options.opacity + 0.1);
            self._update({ opacity: newOpacity });
            break;
          case 'PageDown':
          case 34:
            newOpacity = Math.max(0, self.options.opacity - 0.1);
            self._update({ opacity: newOpacity });
            break;
          case 'KeyV':
          case 86:
          case 'v':
          case 'V':
            self._update({
              use: !self.options.use
            });
            break;
        }
      }).on('keyup blur', self.stopDragging);

      const drag = {
        x: 0,
        y: 0
      };
      self.elements.visibles.data(
        'dragging', false
      ).on('mousedown pointerdown', function(evt) {
        evt.preventDefault();
        $(this).data('dragging', true);
        drag.x = evt.clientX;
        drag.y = evt.clientY;
        evt.stopPropagation();
      }).on('mouseup pointerup', function(evt) {
        evt.preventDefault();
        $(this).data('dragging', false);
        evt.stopPropagation();
      }).on('mousemove pointermove', function(evt) {
        evt.preventDefault();
        if ($(this).data('dragging')) {
          if (!evt.ctrlKey && !evt.altKey) {
            self.stopDragging();
            return;
          }
          const pxOld = board.fromScreen(drag.x, drag.y);
          const pxNew = board.fromScreen(evt.clientX, evt.clientY);
          const dx = (pxNew.x) - (pxOld.x);
          const dy = (pxNew.y) - (pxOld.y);
          const newX = self.options.x + dx;
          const newY = self.options.y + dy;
          self._update({ x: newX, y: newY });
          query.set({ ox: newX, oy: newY }, true);
          if (dx !== 0) {
            drag.x = evt.clientX;
          }
          if (dy !== 0) {
            drag.y = evt.clientY;
          }
        }
      });

      self.elements.styleImage.on('load', function(e) {
        self.loadStyle(!self.loading);
      });

      self.elements.sourceImage.on('load', (e) => {
        self.loading = false;
        self.rasterizeTemplate();
        if (!(self.options.width >= 0)) {
          self.elements.widthInput.val(self.elements.sourceImage[0].naturalWidth);
        }
        self.elements.visibles.toggleClass('pixelate', query.get('scale') > self.getWidthRatio());
      }).on('error', () => {
        self.loading = false;
        self.elements.imageErrorWarning.show();
        self.elements.imageErrorWarning.text('There was an error getting the image');
        self._update({ use: false });
      });

      if (board.update(true)) {
        return;
      }
      board.getRenderBoard().parent().prepend(self.elements.visibles);
    },
    webinit: function(data) {
      self.initGl(self.elements.template[0].getContext('webgl', {
        premultipliedAlpha: false
      }), data.palette);

      self.initCORS(data.corsBase, data.corsParam);

      if (!self.loading) {
        self.rasterizeTemplate();
      }
    },
    stopDragging: function() {
      if (self.options.use) {
        self.elements.visibles.css('pointer-events', 'none').data('dragging', false);
      }
    },
    setPixelated: function(pixelate = true) {
      self.elements.visibles.toggleClass('pixelate', pixelate);
    },
    getWidthRatio: function() {
      if (self.usesStyle()) {
        return self.getInternalWidth() / self.getDisplayWidth();
      } else {
        return self.getSourceWidth() / self.getDisplayWidth();
      }
    },
    getDownscaleWidthRatio: function() {
      return self.getSourceWidth() / self.getDisplayWidth();
    },
    getDownscaleHeightRatio: function() {
      return self.getSourceHeight() / self.getDisplayHeight();
    },
    getDisplayWidth: function() {
      return Math.round(self.options.width >= 0 ? self.options.width : self.getSourceWidth());
    },
    getDisplayHeight: function() {
      return Math.round(self.getDisplayWidth() * self.getAspectRatio());
    },
    getStyleWidth: function() {
      return self.elements.styleImage[0].naturalWidth / STYLES_X;
    },
    getStyleHeight: function() {
      return self.elements.styleImage[0].naturalHeight / STYLES_Y;
    },
    getSourceWidth: function() {
      return self.elements.sourceImage[0].naturalWidth;
    },
    getSourceHeight: function() {
      return self.elements.sourceImage[0].naturalHeight;
    },
    getAspectRatio: function() {
      return self.getSourceWidth() === 0 ? 1 : self.getSourceHeight() / self.getSourceWidth();
    },
    getInternalWidth: function() {
      return self.getDisplayWidth() * self.getStyleWidth();
    },
    getInternalHeight: function() {
      return self.getDisplayHeight() * self.getStyleHeight();
    },
    loadStyle: function(redraw = true) {
      const style = self.elements.styleImage[0];
      if (self.gl.context !== null && style.naturalWidth !== 0 && style.naturalHeight !== 0) {
        self.gl.context.activeTexture(self.gl.context.TEXTURE1);
        self.gl.context.bindTexture(self.gl.context.TEXTURE_2D, self.gl.textures.style);
        self.gl.context.texImage2D(
          self.gl.context.TEXTURE_2D,
          0,
          self.gl.context.ALPHA,
          self.gl.context.ALPHA,
          self.gl.context.UNSIGNED_BYTE,
          style
        );
        if (redraw) {
          self.stylizeTemplate();
        }
      }
    },
    initGl: function(context, palette) {
      self.gl.context = context;
      if (self.gl.context === null) {
        console.info('WebGL is unsupported on this system');
        return;
      }
      self.gl.context.clearColor(0, 0, 0, 0);
      // self.gl.context.pixelStorei(self.gl.context.UNPACK_COLORSPACE_CONVERSION_WEBGL, self.gl.context.NONE);
      self.gl.context.pixelStorei(self.gl.context.UNPACK_FLIP_Y_WEBGL, true);
      self.gl.textures.source = self.createGlTexture();
      self.gl.textures.downscaled = self.createGlTexture();
      self.gl.framebuffers.intermediate = self.gl.context.createFramebuffer();
      self.gl.context.bindFramebuffer(self.gl.context.FRAMEBUFFER, self.gl.framebuffers.intermediate);
      self.gl.context.framebufferTexture2D(
        self.gl.context.FRAMEBUFFER,
        self.gl.context.COLOR_ATTACHMENT0,
        self.gl.context.TEXTURE_2D,
        self.gl.textures.downscaled,
        0
      );
      self.gl.textures.style = self.createGlTexture();
      self.loadStyle(false);
      self.gl.buffers.vertex = self.gl.context.createBuffer();
      self.gl.context.bindBuffer(self.gl.context.ARRAY_BUFFER, self.gl.buffers.vertex);
      self.gl.context.bufferData(
        self.gl.context.ARRAY_BUFFER,
        new Float32Array([
          -1, -1,
          -1, 1,
          1, -1,
          1, 1
        ]),
        self.gl.context.STATIC_DRAW
      );
      const identityVertexShader = `
        attribute vec2 a_Pos;
        varying vec2 v_TexCoord;
        void main() {
          v_TexCoord = a_Pos * vec2(0.5, 0.5) + vec2(0.5, 0.5);
          gl_Position = vec4(a_Pos, 0.0, 1.0);
        }
      `;
      const paletteDefs = `
        #define PALETTE_LENGTH ${palette.length}
        #define PALETTE_MAXSIZE 255.0
        #define PALETTE_TRANSPARENT (PALETTE_MAXSIZE - 1.0) / PALETTE_MAXSIZE
        #define PALETTE_UNKNOWN 1.0
      `;
      const diffCustom = `
        #define LUMA_WEIGHTS vec3(0.299, 0.587, 0.114)
        // a simple custom colorspace that stores:
        // - brightness
        // - red/green-ness
        // - blue/yellow-ness
        // this storing of contrasts is similar to how humans
        // see color difference and provides a simple difference function
        // with decent results.
        vec3 rgb2Custom(vec3 rgb) {
          return vec3(
            length(rgb * LUMA_WEIGHTS),
            rgb.r - rgb.g,
            rgb.b - (rgb.r + rgb.g) / 2.0
          );
        }
        float diffCustom(vec3 col1, vec3 col2) {
          return length(rgb2Custom(col1) - rgb2Custom(col2));
        }
      `;
      const downscalingFragmentShader = (comparisonFunctionName = null) => `
        precision mediump float;
        // GLES (and thus WebGL) does not support dynamic for loops
        // the workaround is to specify the condition as an upper bound
        // then break the loop early if we reach our dynamic limit 
        #define MAX_SAMPLE_SIZE 16.0
        
        ${paletteDefs}
        ${comparisonFunctionName !== null ? '#define CONVERT_COLORS' : ''}
        #define HIGHEST_DIFF 999999.9
        uniform sampler2D u_Template;
        uniform vec2 u_TexelSize;
        uniform vec2 u_SampleSize;
        uniform vec3 u_Palette[PALETTE_LENGTH];
        varying vec2 v_TexCoord;
        const float epsilon = 1.0 / 128.0;
        // The alpha channel is used to index the palette: 
        const vec4 transparentColor = vec4(0.0, 0.0, 0.0, PALETTE_TRANSPARENT);
        ${diffCustom}
        void main () {
          vec4 color = vec4(0.0);
          vec2 actualSampleSize = min(u_SampleSize, vec2(MAX_SAMPLE_SIZE));
          vec2 sampleTexSize = u_TexelSize / actualSampleSize;
          // sample is taken from center of fragment
          // this moves the coordinates to the starting corner and to the center of the sample texel
          vec2 sampleOrigin = v_TexCoord - sampleTexSize * (actualSampleSize / 2.0 - 0.5);
          float sampleCount = 0.0;
          for(float x = 0.0; x < MAX_SAMPLE_SIZE; x++) {
            if(x >= u_SampleSize.x) {
              break;
            }
            for(float y = 0.0; y < MAX_SAMPLE_SIZE; y++) {
              if(y >= u_SampleSize.y) {
                break;
              }
              vec2 pos = sampleOrigin + sampleTexSize * vec2(x, y);
              vec4 sample = texture2D(u_Template, pos);
              // pxlsfiddle uses the alpha channel of the first pixel to store
              // scale information. This can affect color sampling, so drop the
              // top-left-most subtexel unless its alpha is typical (1 or 0 exactly).
              if(x == 0.0 && y == 0.0
                && pos.x < u_TexelSize.x && (1.0 - pos.y) < u_TexelSize.y
                && sample.a != 1.0) {
                continue;
              }
              if(sample.a == 0.0) {
                continue;
              }
              color += sample;
              sampleCount++;
            }
          }
          if(sampleCount == 0.0) {
            gl_FragColor = transparentColor;
            return;
          }
          color /= sampleCount;
          #ifdef CONVERT_COLORS
            float bestDiff = HIGHEST_DIFF;
            int bestIndex = int(PALETTE_MAXSIZE);
            vec3 bestColor = vec3(0.0);
            for(int i = 0; i < PALETTE_LENGTH; i++) {
              float diff = ${comparisonFunctionName}(color.rgb, u_Palette[i]);
              if(diff < bestDiff) {
                bestDiff = diff;
                bestIndex = i;
                bestColor = u_Palette[i];
              }
            }
            gl_FragColor = vec4(bestColor, float(bestIndex) / PALETTE_MAXSIZE);
          #else
            for(int i = 0; i < PALETTE_LENGTH; i++) {
              if(all(lessThan(abs(u_Palette[i] - color.rgb), vec3(epsilon)))) {
                gl_FragColor = vec4(u_Palette[i], float(i) / PALETTE_MAXSIZE);
                return;
              }
            }
            gl_FragColor = vec4(color.rgb, PALETTE_UNKNOWN);
          #endif
        }
      `;
      self.gl.programs.downscaling.unconverted = self.createGlProgram(identityVertexShader, downscalingFragmentShader(null));
      self.gl.programs.downscaling.nearestCustom = self.createGlProgram(identityVertexShader, downscalingFragmentShader('diffCustom'));
      const int2rgb = i => [(i >> 16) & 0xFF, (i >> 8) & 0xFF, i & 0xFF];
      const paletteBuffer = new Float32Array(palette.flatMap(c => int2rgb(parseInt(c.value, 16)).map(c => c / 255)));
      for (const program of Object.values(self.gl.programs.downscaling)) {
        self.gl.context.useProgram(program);
        const posLocation = self.gl.context.getAttribLocation(program, 'a_Pos');
        self.gl.context.vertexAttribPointer(posLocation, 2, self.gl.context.FLOAT, false, 0, 0);
        self.gl.context.enableVertexAttribArray(posLocation);
        self.gl.context.uniform1i(self.gl.context.getUniformLocation(program, 'u_Template'), 0);
        self.gl.context.uniform3fv(self.gl.context.getUniformLocation(program, 'u_Palette'), paletteBuffer);
      }
      self.gl.programs.stylize = self.createGlProgram(identityVertexShader, `
        precision mediump float;
        #define STYLES_X float(${STYLES_X})
        #define STYLES_Y float(${STYLES_Y})
        ${paletteDefs}
        uniform sampler2D u_Template;
        uniform sampler2D u_Style;
        uniform vec2 u_TexelSize;
        varying vec2 v_TexCoord;
        const vec2 styleSize = vec2(1.0 / STYLES_X, 1.0 / STYLES_Y);
        void main () {
          vec4 templateSample = texture2D(u_Template, v_TexCoord);
          float index = floor(templateSample.a * PALETTE_MAXSIZE + 0.5);
          vec2 indexCoord = vec2(mod(index, STYLES_X), STYLES_Y - floor(index / STYLES_Y) - 1.0);
          vec2 subTexCoord = mod(v_TexCoord, u_TexelSize) / u_TexelSize;
          vec2 styleCoord = (indexCoord + subTexCoord) * styleSize;
          
          vec4 styleMask = vec4(1.0, 1.0, 1.0, texture2D(u_Style, styleCoord).a);
          gl_FragColor = vec4(templateSample.rgb, templateSample.a == PALETTE_TRANSPARENT ? 0.0 : 1.0) * styleMask;
        }
      `);
      self.gl.context.useProgram(self.gl.programs.stylize);
      const stylePosLocation = self.gl.context.getAttribLocation(self.gl.programs.stylize, 'a_Pos');
      self.gl.context.vertexAttribPointer(stylePosLocation, 2, self.gl.context.FLOAT, false, 0, 0);
      self.gl.context.enableVertexAttribArray(stylePosLocation);
      self.gl.context.uniform1i(self.gl.context.getUniformLocation(self.gl.programs.stylize, 'u_Template'), 0);
      self.gl.context.uniform1i(self.gl.context.getUniformLocation(self.gl.programs.stylize, 'u_Style'), 1);
    },
    createGlProgram: function(vertexSource, fragmentSource) {
      const program = self.gl.context.createProgram();
      self.gl.context.attachShader(program, self.createGlShader(self.gl.context.VERTEX_SHADER, vertexSource));
      self.gl.context.attachShader(program, self.createGlShader(self.gl.context.FRAGMENT_SHADER, fragmentSource));
      self.gl.context.linkProgram(program);
      if (!self.gl.context.getProgramParameter(program, self.gl.context.LINK_STATUS)) {
        throw new Error('Failed to link WebGL template program:\n\n' + self.gl.context.getProgramInfoLog(program));
      }
      return program;
    },
    createGlShader: function(type, source) {
      const shader = self.gl.context.createShader(type);
      self.gl.context.shaderSource(shader, source);
      self.gl.context.compileShader(shader);
      if (!self.gl.context.getShaderParameter(shader, self.gl.context.COMPILE_STATUS)) {
        throw new Error('Failed to compile WebGL template shader:\n\n' + self.gl.context.getShaderInfoLog(shader));
      }
      return shader;
    },
    createGlTexture: function() {
      const texture = self.gl.context.createTexture();
      self.gl.context.bindTexture(self.gl.context.TEXTURE_2D, texture);
      self.gl.context.texParameteri(self.gl.context.TEXTURE_2D, self.gl.context.TEXTURE_WRAP_S, self.gl.context.CLAMP_TO_EDGE);
      self.gl.context.texParameteri(self.gl.context.TEXTURE_2D, self.gl.context.TEXTURE_WRAP_T, self.gl.context.CLAMP_TO_EDGE);
      self.gl.context.texParameteri(self.gl.context.TEXTURE_2D, self.gl.context.TEXTURE_MIN_FILTER, self.gl.context.NEAREST);
      self.gl.context.texParameteri(self.gl.context.TEXTURE_2D, self.gl.context.TEXTURE_MAG_FILTER, self.gl.context.NEAREST);
      return texture;
    },
    rasterizeTemplate: function() {
      self.downscaleTemplate();
      self.stylizeTemplate();
    },
    downscaleTemplate: function() {
      const width = self.getDisplayWidth();
      const height = self.getDisplayHeight();
      if (self.gl.context == null || width === 0 || height === 0) {
        return;
      }
      // set the framebuffer size before rendering to it
      self.gl.context.activeTexture(self.gl.context.TEXTURE0);
      self.gl.context.bindTexture(self.gl.context.TEXTURE_2D, self.gl.textures.downscaled);
      self.gl.context.texImage2D(
        self.gl.context.TEXTURE_2D,
        0,
        self.gl.context.RGBA,
        width,
        height,
        0,
        self.gl.context.RGBA,
        self.gl.context.UNSIGNED_BYTE,
        null
      );

      self.gl.context.bindFramebuffer(self.gl.context.FRAMEBUFFER, self.gl.framebuffers.intermediate);
      self.gl.context.clear(self.gl.context.COLOR_BUFFER_BIT);
      self.gl.context.viewport(0, 0, width, height);

      const program = self.gl.programs.downscaling[self.options.convertMode];

      self.gl.context.useProgram(program);

      self.gl.context.uniform2f(
        self.gl.context.getUniformLocation(program, 'u_SampleSize'),
        Math.max(1, self.getDownscaleWidthRatio()),
        Math.max(1, self.getDownscaleHeightRatio())
      );
      self.gl.context.uniform2f(
        self.gl.context.getUniformLocation(program, 'u_TexelSize'),
        1 / self.getDisplayWidth(),
        1 / self.getDisplayHeight()
      );

      self.gl.context.bindTexture(self.gl.context.TEXTURE_2D, self.gl.textures.source);

      const texture = self.elements.sourceImage[0];

      self.gl.context.texImage2D(
        self.gl.context.TEXTURE_2D,
        0,
        self.gl.context.RGBA,
        self.gl.context.RGBA,
        self.gl.context.UNSIGNED_BYTE,
        texture
      );

      self.gl.context.drawArrays(self.gl.context.TRIANGLE_STRIP, 0, 4);
    },
    stylizeTemplate: function() {
      self.updateSize();

      const width = self.getInternalWidth();
      const height = self.getInternalHeight();

      if (self.gl.context == null || width === 0 || height === 0) {
        return;
      }

      self.gl.context.bindFramebuffer(self.gl.context.FRAMEBUFFER, self.gl.framebuffers.main);
      self.gl.context.clear(self.gl.context.COLOR_BUFFER_BIT);
      self.gl.context.viewport(0, 0, width, height);

      self.gl.context.useProgram(self.gl.programs.stylize);

      self.gl.context.uniform2f(
        self.gl.context.getUniformLocation(self.gl.programs.stylize, 'u_TexelSize'),
        1 / self.getDisplayWidth(),
        1 / self.getDisplayHeight()
      );

      self.gl.context.activeTexture(self.gl.context.TEXTURE0);
      self.gl.context.bindTexture(self.gl.context.TEXTURE_2D, self.gl.textures.downscaled);

      self.gl.context.activeTexture(self.gl.context.TEXTURE1);
      self.gl.context.bindTexture(self.gl.context.TEXTURE_2D, self.gl.textures.style);

      self.gl.context.drawArrays(self.gl.context.TRIANGLE_STRIP, 0, 4);
    }
  };
  return {
    normalizeTemplateObj: self.normalizeTemplateObj,
    update: self._update,
    draw: self.draw,
    init: self.init,
    webinit: self.webinit,
    queueUpdate: self.queueUpdate,
    getOptions: () => self.options,
    setPixelated: self.setPixelated,
    getDisplayWidth: self.getDisplayWidth,
    getDisplayHeight: self.getDisplayHeight,
    getWidthRatio: self.getWidthRatio
  };
})();
