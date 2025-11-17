(() => {
  // Override navigator.webdriver to undefined (most important)
  Object.defineProperty(navigator, 'webdriver', {
    get: () => undefined,
    configurable: true,
  });

  // Override navigator.plugins and navigator.mimeTypes with realistic data
  const createPluginArray = (): PluginArray => {
    const plugins = [
      {
        name: 'Chrome PDF Plugin',
        filename: 'internal-pdf-viewer',
        description: 'Portable Document Format',
        length: 1,
      },
      {
        name: 'Chrome PDF Viewer',
        filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai',
        description: '',
        length: 1,
      },
      {
        name: 'Native Client',
        filename: 'internal-nacl-plugin',
        description: '',
        length: 2,
      },
    ];

    const pluginArray = Object.create(PluginArray.prototype);
    pluginArray.length = plugins.length;
    plugins.forEach((plugin, index) => {
      pluginArray[index] = plugin;
      pluginArray[plugin.name] = plugin;
    });
    pluginArray.item = (index: number) => pluginArray[index] || null;
    pluginArray.namedItem = (name: string) => pluginArray[name] || null;
    pluginArray.refresh = () => {};

    return pluginArray;
  };

  Object.defineProperty(navigator, 'plugins', {
    get: () => createPluginArray(),
    configurable: true,
  });

  Object.defineProperty(navigator, 'mimeTypes', {
    get: () => {
      const mimeTypes = Object.create(MimeTypeArray.prototype);
      mimeTypes.length = 4;
      mimeTypes.item = () => null;
      mimeTypes.namedItem = () => null;
      return mimeTypes;
    },
    configurable: true,
  });

  // Override chrome object to appear as real Chrome
  if (!(window as any).chrome) {
    (window as any).chrome = {};
  }

  (window as any).chrome.runtime = {
    OnInstalledReason: {
      CHROME_UPDATE: 'chrome_update',
      INSTALL: 'install',
      SHARED_MODULE_UPDATE: 'shared_module_update',
      UPDATE: 'update',
    },
    OnRestartRequiredReason: {
      APP_UPDATE: 'app_update',
      OS_UPDATE: 'os_update',
      PERIODIC: 'periodic',
    },
    PlatformArch: {
      ARM: 'arm',
      ARM64: 'arm64',
      MIPS: 'mips',
      MIPS64: 'mips64',
      X86_32: 'x86-32',
      X86_64: 'x86-64',
    },
    PlatformNaclArch: {
      ARM: 'arm',
      MIPS: 'mips',
      MIPS64: 'mips64',
      X86_32: 'x86-32',
      X86_64: 'x86-64',
    },
    PlatformOs: {
      ANDROID: 'android',
      CROS: 'cros',
      LINUX: 'linux',
      MAC: 'mac',
      OPENBSD: 'openbsd',
      WIN: 'win',
    },
    RequestUpdateCheckStatus: {
      NO_UPDATE: 'no_update',
      THROTTLED: 'throttled',
      UPDATE_AVAILABLE: 'update_available',
    },
  };

  (window as any).chrome.loadTimes = function () {
    return {
      commitLoadTime: performance.timing.responseStart / 1000,
      connectionInfo: 'http/1.1',
      finishDocumentLoadTime: performance.timing.domContentLoadedEventEnd / 1000,
      finishLoadTime: performance.timing.loadEventEnd / 1000,
      firstPaintAfterLoadTime: 0,
      firstPaintTime: performance.timing.responseStart / 1000,
      navigationType: 'Other',
      npnNegotiatedProtocol: 'h2',
      requestTime: performance.timing.requestStart / 1000,
      startLoadTime: performance.timing.requestStart / 1000,
      wasAlternateProtocolAvailable: false,
      wasFetchedViaSpdy: true,
      wasNpnNegotiated: true,
    };
  };

  (window as any).chrome.csi = function () {
    return {
      onloadT: performance.timing.loadEventEnd,
      pageT: performance.timing.loadEventEnd - performance.timing.navigationStart,
      startE: performance.timing.navigationStart,
      tran: 15,
    };
  };

  (window as any).chrome.app = {
    isInstalled: false,
    InstallState: {
      DISABLED: 'disabled',
      INSTALLED: 'installed',
      NOT_INSTALLED: 'not_installed',
    },
    RunningState: {
      CANNOT_RUN: 'cannot_run',
      READY_TO_RUN: 'ready_to_run',
      RUNNING: 'running',
    },
  };

  // Mask WebGL fingerprinting
  const getParameterOriginal = WebGLRenderingContext.prototype.getParameter;
  WebGLRenderingContext.prototype.getParameter = function (parameter) {
    if (parameter === 37445) {
      // UNMASKED_VENDOR_WEBGL
      return 'Intel Inc.';
    }
    if (parameter === 37446) {
      // UNMASKED_RENDERER_WEBGL
      return 'Intel Iris OpenGL Engine';
    }
    return getParameterOriginal.call(this, parameter);
  };

  const getParameterOriginal2 = WebGL2RenderingContext.prototype.getParameter;
  WebGL2RenderingContext.prototype.getParameter = function (parameter) {
    if (parameter === 37445) {
      return 'Intel Inc.';
    }
    if (parameter === 37446) {
      return 'Intel Iris OpenGL Engine';
    }
    return getParameterOriginal2.call(this, parameter);
  };

  // Override navigator.permissions.query for consistent responses
  const originalQuery = navigator.permissions.query;
  navigator.permissions.query = function (parameters: PermissionDescriptor) {
    if (parameters.name === 'notifications') {
      return Promise.resolve({ state: 'prompt' } as PermissionStatus);
    }
    return originalQuery.call(navigator.permissions, parameters);
  };

  // Add realistic navigator.languages
  Object.defineProperty(navigator, 'languages', {
    get: () => ['en-US', 'en'],
    configurable: true,
  });

  // Override navigator.platform
  Object.defineProperty(navigator, 'platform', {
    get: () => 'Win32',
    configurable: true,
  });

  // Override navigator.hardwareConcurrency to a realistic value
  Object.defineProperty(navigator, 'hardwareConcurrency', {
    get: () => 8,
    configurable: true,
  });

  // Override navigator.deviceMemory to a realistic value
  Object.defineProperty(navigator, 'deviceMemory', {
    get: () => 8,
    configurable: true,
  });

  // Override navigator.maxTouchPoints
  Object.defineProperty(navigator, 'maxTouchPoints', {
    get: () => 0,
    configurable: true,
  });

  // Override the connection property
  if ((navigator as any).connection || (navigator as any).mozConnection || (navigator as any).webkitConnection) {
    Object.defineProperty(navigator, 'connection', {
      get: () => ({
        effectiveType: '4g',
        rtt: 50,
        downlink: 10,
        saveData: false,
      }),
      configurable: true,
    });
  }

  // Override battery API to avoid detection
  if ((navigator as any).getBattery) {
    const originalGetBattery = (navigator as any).getBattery;
    (navigator as any).getBattery = function () {
      return originalGetBattery.call(navigator).then((battery: any) => {
        Object.defineProperty(battery, 'charging', { get: () => true });
        Object.defineProperty(battery, 'chargingTime', { get: () => 0 });
        Object.defineProperty(battery, 'dischargingTime', { get: () => Infinity });
        Object.defineProperty(battery, 'level', { get: () => 1.0 });
        return battery;
      });
    };
  }

  // Modify the Date object to avoid timezone fingerprinting inconsistencies
  const originalDate = Date;
  const originalGetTimezoneOffset = Date.prototype.getTimezoneOffset;
  Date.prototype.getTimezoneOffset = function () {
    return 300; // EST/EDT offset in minutes (-5 hours = 300 minutes)
  };

  // Override Notification.permission
  if ('Notification' in window) {
    try {
      Object.defineProperty(Notification, 'permission', {
        get: () => 'default',
        configurable: true,
      });
    } catch (e) {
      // Ignore if already defined
    }
  }

  // Remove automation-related properties
  delete (navigator as any).__driver_evaluate;
  delete (navigator as any).__webdriver_evaluate;
  delete (navigator as any).__selenium_evaluate;
  delete (navigator as any).__fxdriver_evaluate;
  delete (navigator as any).__driver_unwrapped;
  delete (navigator as any).__webdriver_unwrapped;
  delete (navigator as any).__selenium_unwrapped;
  delete (navigator as any).__fxdriver_unwrapped;
  delete (navigator as any).__webdriver_script_fn;
  delete (navigator as any).__webdriver_script_func;
  delete (navigator as any).__webdriver_script_function;

  // Remove document automation properties
  delete (document as any).__selenium_unwrapped;
  delete (document as any).__driver_unwrapped;
  delete (document as any).__webdriver_unwrapped;
  delete (document as any).__fxdriver_unwrapped;
  delete (document as any).__driver_evaluate;
  delete (document as any).__webdriver_evaluate;
  delete (document as any).__selenium_evaluate;
  delete (document as any).__fxdriver_evaluate;

  // Remove window automation properties
  delete (window as any)._phantom;
  delete (window as any).callPhantom;
  delete (window as any).__nightmare;
  delete (window as any).__selenium;
  delete (window as any).webdriver;
  delete (window as any)._Selenium_IDE_Recorder;

  // Override toString to hide modifications
  const toStringOverride = function (original: Function, name: string) {
    const handler = {
      apply: function (target: any, thisArg: any, argumentsList: any[]) {
        return original.apply(thisArg, argumentsList);
      },
      get: function (target: any, prop: string) {
        if (prop === 'toString') {
          return () => `function ${name}() { [native code] }`;
        }
        return target[prop];
      },
    };
    return new Proxy(original, handler);
  };

  // Apply toString override to key functions
  navigator.permissions.query = toStringOverride(
    navigator.permissions.query,
    'query'
  );

  console.log('[Stealth] Anti-detection measures applied');
})();

