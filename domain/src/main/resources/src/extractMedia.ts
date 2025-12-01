(() => {
  // ============ SHARED TYPES ============
  type IconResult = { base64: string; cssSelectors: string[] };
  type ImageResult = { base64: string; cssSelectors: string[] };
  type FailedImage = { cssSelector: string; reason: string };

  type IconSkippedDetail = {
    tag: string;
    classes: string;
    beforeContent?: string;
    textContent?: string;
    width?: number;
    height?: number;
    error?: string;
    reason: 'no_glyph' | 'zero_dimensions' | 'rendering_error';
  };

  type IconDebugStats = {
    totalElementsFound: number;
    elementsBySelector: Record<string, number>;
    elementsProcessed: number;
    skippedNoGlyph: number;
    skippedSvgZeroSize: number;
    renderingErrors: number;
    successfullyRendered: number;
    uniqueIcons: number;
    totalSnippets: number;
    skippedDetails: IconSkippedDetail[];
    deduplicationMap: Record<number, number>;
  };

  type MediaExtractionResult = {
    icons: {
      debug: IconDebugStats;
      results: IconResult[];
    };
    images: {
      successful: ImageResult[];
      failed: FailedImage[];
    };
  };

  // Shared ID counter for all elements
  let globalIdCounter = 0;

  // ============ SHARED UTILITIES ============
  const extractStableId = (el: Element): string | null => {
    return (el as HTMLElement).getAttribute('data-ds-id');
  };

  const toPlainString = (cssContent: string | null): string => {
    if (!cssContent || cssContent === 'none') return '';
    let s = String(cssContent).trim();
    if ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith('\'') && s.endsWith('\''))) {
      s = s.slice(1, -1);
    }
    if (/^\\[0-9a-fA-F]{1,6}$/.test(s)) {
      const hex = s.replace('\\', '');
      try {
        const codePoint = parseInt(hex, 16);
        return String.fromCodePoint(codePoint);
      } catch (_e) {
        return '';
      }
    }
    return s;
  };

  const getContrastingBackground = (color: string): string => {
    const div = document.createElement('div');
    div.style.color = color;
    document.body.appendChild(div);
    const computedColor = window.getComputedStyle(div).color;
    document.body.removeChild(div);
    
    const rgbMatch = computedColor.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/);
    if (!rgbMatch) return '#f0f0f0';
    
    const r = parseInt(rgbMatch[1]);
    const g = parseInt(rgbMatch[2]);
    const b = parseInt(rgbMatch[3]);
    
    const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    return luminance > 0.5 ? '#2a2a2a' : '#f0f0f0';
  };

  // ============ ICON EXTRACTION ============
  const renderIcon = async (el: Element, debugStats: IconDebugStats): Promise<string | null> => {
    const style = window.getComputedStyle(el as HTMLElement);
    const before = window.getComputedStyle(el as HTMLElement, '::before');
    const beforeContent = before.content;
    const textContent = (el.textContent || '').trim();
    const glyph = toPlainString(beforeContent) || textContent;
    
    if (!glyph) {
      debugStats.skippedNoGlyph++;
      debugStats.skippedDetails.push({
        tag: el.tagName.toLowerCase(),
        classes: typeof el.className === 'string' ? el.className : (el.className as SVGAnimatedString).baseVal || '',
        beforeContent: beforeContent,
        textContent: textContent,
        reason: 'no_glyph'
      });
      return null;
    }

    await (document as any).fonts.ready;

    const font =
      style.font ||
      `${style.fontStyle || 'normal'} ${style.fontVariant || 'normal'} ${
        style.fontWeight || '400'
      } ${style.fontSize || '16px'} / ${style.lineHeight || 'normal'} ${
        style.fontFamily || 'sans-serif'
      }`;
    const fontSizePx = parseFloat(style.fontSize || '16');
    const fill = style.color;

    const measureCanvas = document.createElement('canvas');
    const measureCtx = measureCanvas.getContext('2d');
    if (!measureCtx) return null;
    measureCtx.font = font;
    const metrics = measureCtx.measureText(glyph);
    const width = Math.ceil(Math.max(metrics.width, fontSizePx) * 1.5);
    const height = Math.ceil(fontSizePx * 1.8);

    const scale = 1;
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.floor(width * scale));
    canvas.height = Math.max(1, Math.floor(height * scale));
    const ctx = canvas.getContext('2d');
    if (!ctx) return null;
    ctx.scale(scale, scale);
    
    const backgroundColor = getContrastingBackground(fill);
    ctx.fillStyle = backgroundColor;
    ctx.fillRect(0, 0, width, height);
    
    ctx.fillStyle = fill;
    ctx.font = font;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(glyph, width / 2, height / 2);

    const dataUrl = canvas.toDataURL('image/webp', 0.9);
    const base64 = dataUrl.replace(/^data:[^,]+,/, '');
    return base64;
  };

  const renderSvgIcon = async (el: Element, debugStats: IconDebugStats): Promise<string | null> => {
    const svg = el as SVGElement;
    
    let width = 0;
    let height = 0;
    
    const widthAttr = svg.getAttribute('width');
    const heightAttr = svg.getAttribute('height');
    if (widthAttr && heightAttr) {
      width = parseFloat(widthAttr);
      height = parseFloat(heightAttr);
    }
    
    if (width === 0 || height === 0) {
      const viewBox = svg.getAttribute('viewBox');
      if (viewBox) {
        const parts = viewBox.split(/\s+|,/);
        if (parts.length === 4) {
          width = parseFloat(parts[2]);
          height = parseFloat(parts[3]);
        }
      }
    }
    
    if (width === 0 || height === 0) {
      const bbox = svg.getBoundingClientRect();
      if (bbox.width > 0 && bbox.height > 0) {
        width = bbox.width;
        height = bbox.height;
      }
    }
    
    if (width === 0 || height === 0) {
      try {
        const svgCloneForMeasure = svg.cloneNode(true) as SVGElement;
        svgCloneForMeasure.style.position = 'absolute';
        svgCloneForMeasure.style.visibility = 'hidden';
        svgCloneForMeasure.style.display = 'block';
        svgCloneForMeasure.style.left = '-9999px';
        svgCloneForMeasure.style.top = '-9999px';
        
        document.body.appendChild(svgCloneForMeasure);
        const bbox = svgCloneForMeasure.getBoundingClientRect();
        document.body.removeChild(svgCloneForMeasure);
        
        if (bbox.width > 0 && bbox.height > 0) {
          width = bbox.width;
          height = bbox.height;
        }
      } catch (_e) {
        // Failed to measure
      }
    }
    
    if (width === 0 || height === 0) {
      width = 24;
      height = 24;
    }
    
    if (width < 1 || height < 1) {
      debugStats.skippedSvgZeroSize++;
      debugStats.skippedDetails.push({
        tag: 'svg',
        classes: typeof el.className === 'string' ? el.className : (el.className as SVGAnimatedString).baseVal || '',
        width: width,
        height: height,
        reason: 'zero_dimensions'
      });
      return null;
    }
    
    const style = window.getComputedStyle(svg);
    const fill = style.color || style.fill || '#000000';
    
    const svgClone = svg.cloneNode(true) as SVGElement;
    svgClone.setAttribute('width', Math.ceil(width).toString());
    svgClone.setAttribute('height', Math.ceil(height).toString());
    
    const svgString = new XMLSerializer().serializeToString(svgClone);
    const svgBlob = new Blob([svgString], { type: 'image/svg+xml;charset=utf-8' });
    
    return new Promise<string | null>((resolve) => {
      const img = new Image();
      img.onload = () => {
        const scale = 1;
        const canvas = document.createElement('canvas');
        canvas.width = Math.max(1, Math.floor(width * scale));
        canvas.height = Math.max(1, Math.floor(height * scale));
        const ctx = canvas.getContext('2d');
        if (!ctx) {
          resolve(null);
          return;
        }
        
        ctx.scale(scale, scale);
        
        const backgroundColor = getContrastingBackground(fill);
        ctx.fillStyle = backgroundColor;
        ctx.fillRect(0, 0, width, height);
        
        ctx.drawImage(img, 0, 0, width, height);
        
        const dataUrl = canvas.toDataURL('image/webp', 0.9);
        const base64 = dataUrl.replace(/^data:[^,]+,/, '');
        resolve(base64);
      };
      img.onerror = () => resolve(null);
      img.src = URL.createObjectURL(svgBlob);
      setTimeout(() => resolve(null), 1000);
    });
  };

  const extractIcons = async (): Promise<{ debug: IconDebugStats; results: IconResult[] }> => {
    const imagesToStableIds = new Map<string, Set<string>>();
    
    const debugStats: IconDebugStats = {
      totalElementsFound: 0,
      elementsBySelector: {},
      elementsProcessed: 0,
      skippedNoGlyph: 0,
      skippedSvgZeroSize: 0,
      renderingErrors: 0,
      successfullyRendered: 0,
      uniqueIcons: 0,
      totalSnippets: 0,
      skippedDetails: [],
      deduplicationMap: {}
    };
    
    const selectors = [
      'svg',
      'i.fa', 'i.fas', 'i.far', 'i.fab', 'i.fal', 'i.fad', 'i.fass', 'i.fasr', 'i.fasl',
      'i[class^="fa-"]', 'i[class*=" fa-"]',
      'i.bi', 'i[class^="bi-"]', 'i[class*=" bi-"]',
      'i.mdi', 'i[class^="mdi-"]', 'i[class*=" mdi-"]',
      '.material-icons', '.material-icons-outlined', '.material-icons-round', 
      '.material-icons-sharp', '.material-icons-two-tone',
      '.material-symbols-outlined', '.material-symbols-rounded', '.material-symbols-sharp',
      'ion-icon', 'i[class^="ion-"]', 'i[class*=" ion-"]',
      '.glyphicon',
      'i.ph', 'i[class^="ph-"]', 'i[class*=" ph-"]',
      'i[class^="ri-"]', 'i[class*=" ri-"]',
      'i.la', 'i[class^="la-"]', 'i[class*=" la-"]',
      'i.uil', 'i.uis', 'i.uim', 'i.uib',
      'i.bx', 'i[class^="bx-"]', 'i[class^="bxs-"]', 'i[class^="bxl-"]',
      '.octicon',
      '[data-feather]',
      '[data-lucide]',
      'i[class^="gg-"]', 'i[class*=" gg-"]',
      'i.ti', 'i[class^="ti-"]', 'i[class*=" ti-"]',
      '[class*="heroicon"]',
      'i[class*="icon"]', 'i[class*="Icon"]',
      'span[class*="icon"]', 'span[class*="Icon"]',
      '[class*="icon-"]', '[class*="Icon-"]',
      '[class*="-icon"]', '[class*="-Icon"]',
      '[class*="ico-"]', '[class*="glyph"]',
      '[data-icon]',
      'i[aria-hidden="true"]',
      '[role="img"]:not(img)',
    ];
    
    for (const selector of selectors) {
      const count = document.querySelectorAll(selector).length;
      debugStats.elementsBySelector[selector] = count;
    }
    
    const elements = Array.from(document.querySelectorAll(selectors.join(', ')));
    debugStats.totalElementsFound = elements.length;
    
    console.log('[extractMedia:icons] Starting extraction...');
    console.log('[extractMedia:icons] Total elements found:', debugStats.totalElementsFound);
    
    // Inject stable identifiers
    for (const el of elements) {
      const stableId = `ds-icon-${globalIdCounter++}`;
      (el as HTMLElement).setAttribute('data-ds-id', stableId);
    }
    
    for (const el of elements) {
      debugStats.elementsProcessed++;
      let rendered: string | null = null;
      
      try {
        if (el.tagName.toLowerCase() === 'svg') {
          rendered = await renderSvgIcon(el, debugStats);
        } else {
          rendered = await renderIcon(el, debugStats);
        }
      } catch (error) {
        debugStats.renderingErrors++;
        debugStats.skippedDetails.push({
          tag: el.tagName.toLowerCase(),
          classes: typeof el.className === 'string' ? el.className : (el.className as SVGAnimatedString).baseVal || '',
          error: String(error),
          reason: 'rendering_error'
        });
        console.warn('[extractMedia:icons] Rendering error:', error);
        continue;
      }
      
      if (!rendered) continue;
      
      debugStats.successfullyRendered++;
      
      const stableId = extractStableId(el);
      if (stableId) {
        if (!imagesToStableIds.has(rendered)) {
          imagesToStableIds.set(rendered, new Set());
        }
        imagesToStableIds.get(rendered)!.add(stableId);
      }
    }
    
    debugStats.uniqueIcons = imagesToStableIds.size;
    for (const [base64, ids] of imagesToStableIds.entries()) {
      const selectorCount = ids.size;
      debugStats.totalSnippets += selectorCount;
      debugStats.deduplicationMap[selectorCount] = (debugStats.deduplicationMap[selectorCount] || 0) + 1;
    }
    
    console.log('[extractMedia:icons] Extraction complete');
    console.log('[extractMedia:icons] Unique icons:', debugStats.uniqueIcons);
    
    const results: IconResult[] = Array.from(imagesToStableIds.entries()).map(([base64, ids]) => ({
      base64,
      cssSelectors: Array.from(ids).map(id => `[data-ds-id="${id}"]`)
    }));
    
    return { debug: debugStats, results };
  };

  // ============ IMAGE EXTRACTION ============
  const extractImages = async (): Promise<{ successful: ImageResult[]; failed: FailedImage[] }> => {
    const imagesToStableIds = new Map<string, Set<string>>();
    const failedImages: FailedImage[] = [];
    
    const images = Array.from(document.querySelectorAll('img'));

    // Inject stable identifiers
    images.forEach(img => {
      img.setAttribute('data-ds-id', `ds-image-${globalIdCounter++}`);
    });

    // Wait for all images to load
    await Promise.all(images.map(img => {
      if (img.complete) return Promise.resolve();
      return new Promise<void>((resolve) => {
        img.onload = () => resolve();
        img.onerror = () => resolve();
        setTimeout(() => resolve(), 1000);
      });
    }));

    console.log(`[extractMedia:images] Found ${images.length} img elements to process`);
    
    // Process img elements in parallel
    const imgResults = await Promise.all(images.map(async (img) => {
      const canvas = document.createElement('canvas');
      const ctx = canvas.getContext('2d');
      if (!ctx) return null;
      
      const rect = img.getBoundingClientRect();
      
      let width = Math.ceil(rect.width);
      let height = Math.ceil(rect.height);
      
      if ((width === 0 || height === 0) && img.naturalWidth > 0 && img.naturalHeight > 0) {
        width = img.naturalWidth;
        height = img.naturalHeight;
      }
      
      if (width === 0 || height === 0) return null;
      
      const scale = 1;
      canvas.width = Math.max(1, Math.floor(width * scale));
      canvas.height = Math.max(1, Math.floor(height * scale));
      ctx.scale(scale, scale);
      
      try {
        ctx.drawImage(img, 0, 0, width, height);
        const dataUrl = canvas.toDataURL('image/webp', 0.9);
        const base64 = dataUrl.replace(/^data:[^,]+,/, '');
        
        const stableId = extractStableId(img);
        return { base64, stableId, failed: null };
      } catch (e) {
        if (e instanceof DOMException && e.name === 'SecurityError' && img.src && !img.src.startsWith('data:')) {
          try {
            ctx.clearRect(0, 0, width, height);
            
            const tempImg = new Image();
            tempImg.setAttribute('crossOrigin', 'anonymous');
            
            await new Promise<void>((resolve, reject) => {
              tempImg.onload = () => resolve();
              tempImg.onerror = () => reject(new Error('Failed to load image with crossOrigin'));
              tempImg.src = img.src;
              setTimeout(() => reject(new Error('Timeout loading image with crossOrigin')), 1000);
            });
            
            ctx.drawImage(tempImg, 0, 0, width, height);
            const dataUrl = canvas.toDataURL('image/webp', 0.9);
            const base64 = dataUrl.replace(/^data:[^,]+,/, '');
            
            const stableId = extractStableId(img);
            return { base64, stableId, failed: null };
          } catch (retryError) {
            const errorMsg = retryError instanceof Error ? retryError.message : String(retryError);
            const stableId = extractStableId(img);
            const cssSelector = stableId ? `[data-ds-id="${stableId}"]` : '';
            return {
              base64: null,
              stableId: null,
              failed: { cssSelector, reason: `CORS retry failed: ${errorMsg}` }
            };
          }
        }
        
        const errorMsg = e instanceof Error ? e.message : String(e);
        const stableId = extractStableId(img);
        const cssSelector = stableId ? `[data-ds-id="${stableId}"]` : '';
        return {
          base64: null,
          stableId: null,
          failed: { cssSelector, reason: errorMsg }
        };
      }
    }));
    
    for (const result of imgResults) {
      if (!result) continue;
      
      if (result.failed) {
        failedImages.push(result.failed);
      } else if (result.base64 && result.stableId) {
        if (!imagesToStableIds.has(result.base64)) {
          imagesToStableIds.set(result.base64, new Set());
        }
        imagesToStableIds.get(result.base64)!.add(result.stableId);
      }
    }
    
    // Extract background images
    const elementsWithBackgrounds = Array.from(document.querySelectorAll('*')).filter(el => {
      const style = window.getComputedStyle(el);
      const backgroundImage = style.backgroundImage;
      return backgroundImage && backgroundImage !== 'none' && backgroundImage.startsWith('url(');
    });
    
    elementsWithBackgrounds.forEach(element => {
      (element as HTMLElement).setAttribute('data-ds-id', `ds-image-${globalIdCounter++}`);
    });
    
    console.log(`[extractMedia:images] Found ${elementsWithBackgrounds.length} elements with backgrounds`);
    
    const bgResults = await Promise.all(elementsWithBackgrounds.map(async (element) => {
      const canvas = document.createElement('canvas');
      const ctx = canvas.getContext('2d');
      if (!ctx) return null;
      
      const rect = element.getBoundingClientRect();
      const computedStyle = window.getComputedStyle(element);
      
      if (computedStyle.display === 'none' || computedStyle.visibility === 'hidden' || computedStyle.opacity === '0') {
        return null;
      }
      
      if (rect.width === 0 || rect.height === 0) return null;
      
      const width = Math.ceil(rect.width);
      const height = Math.ceil(rect.height);
      
      const scale = 1;
      canvas.width = Math.max(1, Math.floor(width * scale));
      canvas.height = Math.max(1, Math.floor(height * scale));
      ctx.scale(scale, scale);
      
      try {
        const backgroundImage = computedStyle.backgroundImage;
        const urlMatch = backgroundImage.match(/url\(['"]?([^'"]+)['"]?\)/);
        if (!urlMatch) return null;
        
        const imageUrl = urlMatch[1];
        
        const tempImg = new Image();
        
        await new Promise<void>((resolve, reject) => {
          tempImg.onload = () => resolve();
          tempImg.onerror = () => reject(new Error('Failed to load background image'));
          tempImg.src = imageUrl;
          setTimeout(() => reject(new Error('Timeout loading background image')), 1000);
        });
        
        ctx.drawImage(tempImg, 0, 0, width, height);
        
        try {
          const dataUrl = canvas.toDataURL('image/webp', 0.9);
          const base64 = dataUrl.replace(/^data:[^,]+,/, '');
          
          const stableId = extractStableId(element);
          return { base64, stableId, failed: null };
        } catch (securityError) {
          if (securityError instanceof DOMException && securityError.name === 'SecurityError') {
            ctx.clearRect(0, 0, width, height);
            
            const corsImg = new Image();
            corsImg.setAttribute('crossOrigin', 'anonymous');
            
            await new Promise<void>((resolve, reject) => {
              corsImg.onload = () => resolve();
              corsImg.onerror = () => reject(new Error('Failed to load background image with crossOrigin'));
              corsImg.src = imageUrl;
              setTimeout(() => reject(new Error('Timeout loading background image with crossOrigin')), 1000);
            });
            
            ctx.drawImage(corsImg, 0, 0, width, height);
            const dataUrl = canvas.toDataURL('image/webp', 0.9);
            const base64 = dataUrl.replace(/^data:[^,]+,/, '');
            
            const stableId = extractStableId(element);
            return { base64, stableId, failed: null };
          }
          throw securityError;
        }
      } catch (e) {
        const errorMsg = e instanceof Error ? e.message : String(e);
        const stableId = extractStableId(element);
        const cssSelector = stableId ? `[data-ds-id="${stableId}"]` : '';
        return {
          base64: null,
          stableId: null,
          failed: { cssSelector, reason: errorMsg }
        };
      }
    }));
    
    for (const result of bgResults) {
      if (!result) continue;
      
      if (result.failed) {
        failedImages.push(result.failed);
      } else if (result.base64 && result.stableId) {
        if (!imagesToStableIds.has(result.base64)) {
          imagesToStableIds.set(result.base64, new Set());
        }
        imagesToStableIds.get(result.base64)!.add(result.stableId);
      }
    }
    
    const results: ImageResult[] = Array.from(imagesToStableIds.entries()).map(([base64, ids]) => ({
      base64,
      cssSelectors: Array.from(ids).map(id => `[data-ds-id="${id}"]`)
    }));
    
    return { successful: results, failed: failedImages };
  };

  // ============ MAIN ENTRY POINT ============
  const run = async (): Promise<string> => {
    console.log('[extractMedia] Starting combined media extraction...');
    
    // Run both extractions in parallel
    const [iconsResult, imagesResult] = await Promise.all([
      extractIcons(),
      extractImages()
    ]);
    
    const result: MediaExtractionResult = {
      icons: iconsResult,
      images: imagesResult
    };
    
    console.log('[extractMedia] Combined extraction complete');
    console.log('[extractMedia] Icons:', iconsResult.results.length, 'unique');
    console.log('[extractMedia] Images:', imagesResult.successful.length, 'successful,', imagesResult.failed.length, 'failed');
    
    return JSON.stringify(result);
  };

  return run();
})();

