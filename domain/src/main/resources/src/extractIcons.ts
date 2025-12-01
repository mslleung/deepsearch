(() => {
  type IconResult = { base64: string; cssSelectors: string[] };

  type SkippedDetail = {
    tag: string;
    classes: string;
    beforeContent?: string;
    textContent?: string;
    width?: number;
    height?: number;
    error?: string;
    reason: 'no_glyph' | 'zero_dimensions' | 'rendering_error';
  };

  type DebugStats = {
    totalElementsFound: number;
    elementsBySelector: Record<string, number>;
    elementsProcessed: number;
    skippedNoGlyph: number;
    skippedSvgZeroSize: number;
    renderingErrors: number;
    successfullyRendered: number;
    uniqueIcons: number;
    totalSnippets: number;
    skippedDetails: SkippedDetail[];
    deduplicationMap: Record<number, number>;
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
    // Parse color to RGB values
    const div = document.createElement('div');
    div.style.color = color;
    document.body.appendChild(div);
    const computedColor = window.getComputedStyle(div).color;
    document.body.removeChild(div);
    
    // Extract RGB values from computed color (format: rgb(r, g, b) or rgba(r, g, b, a))
    const rgbMatch = computedColor.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/);
    if (!rgbMatch) return '#f0f0f0'; // Default light gray background
    
    const r = parseInt(rgbMatch[1]);
    const g = parseInt(rgbMatch[2]);
    const b = parseInt(rgbMatch[3]);
    
    // Calculate luminance using relative luminance formula
    const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    
    // If the color is light (luminance > 0.5), use dark background, otherwise use light background
    return luminance > 0.5 ? '#2a2a2a' : '#f0f0f0';
  };

  const renderIcon = async (el: Element, debugStats: DebugStats): Promise<string | null> => {
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
    
    // Choose contrasting background color based on icon color
    const backgroundColor = getContrastingBackground(fill);
    ctx.fillStyle = backgroundColor;
    ctx.fillRect(0, 0, width, height);
    
    // Draw the icon
    ctx.fillStyle = fill;
    ctx.font = font;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(glyph, width / 2, height / 2);

    const dataUrl = canvas.toDataURL('image/webp', 0.9);
    const base64 = dataUrl.replace(/^data:[^,]+,/, '');
    return base64;
  };

  const extractStableId = (el: Element): string | null => {
    return (el as HTMLElement).getAttribute('data-ds-id');
  };

  const renderSvgIcon = async (el: Element, debugStats: DebugStats): Promise<string | null> => {
    const svg = el as SVGElement;
    
    // Try to get intrinsic dimensions from SVG attributes first
    let width = 0;
    let height = 0;
    
    // 1. Try width/height attributes
    const widthAttr = svg.getAttribute('width');
    const heightAttr = svg.getAttribute('height');
    if (widthAttr && heightAttr) {
      width = parseFloat(widthAttr);
      height = parseFloat(heightAttr);
    }
    
    // 2. Try viewBox if no width/height
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
    
    // 3. Try computed dimensions (for visible elements)
    if (width === 0 || height === 0) {
      const bbox = svg.getBoundingClientRect();
      if (bbox.width > 0 && bbox.height > 0) {
        width = bbox.width;
        height = bbox.height;
      }
    }
    
    // 4. Last resort: clone and measure off-screen to avoid triggering page events
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
        // Failed to measure, will use default size
      }
    }
    
    // If still no dimensions, use a default size
    if (width === 0 || height === 0) {
      width = 24;  // Common default icon size
      height = 24;
    }
    
    // Skip only if dimensions are unreasonably small (likely broken SVG)
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
    
    // Get the computed color
    const style = window.getComputedStyle(svg);
    const fill = style.color || style.fill || '#000000';
    
    // Clone the SVG to avoid modifying the original
    const svgClone = svg.cloneNode(true) as SVGElement;

    // Set explicit width/height for rendering
    svgClone.setAttribute('width', Math.ceil(width).toString());
    svgClone.setAttribute('height', Math.ceil(height).toString());
    
    // Serialize SVG to data URL
    const svgString = new XMLSerializer().serializeToString(svgClone);
    const svgBlob = new Blob([svgString], { type: 'image/svg+xml;charset=utf-8' });
    
    // Convert to canvas for WEBP output
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
        
        // Choose contrasting background color based on icon color
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

  const run = async (): Promise<string> => {
    const imagesToStableIds = new Map<string, Set<string>>();
    
    // Initialize debug stats
    const debugStats: DebugStats = {
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
      deduplicationMap: {} // maps number of selectors to count of icons with that many selectors
    };
    
    // Query for multiple icon types with targeted selectors to avoid capturing non-icon elements
    // Previously used broad 'i' selector which captured all italic text
    const selectors = [
      // SVG icons (keep all SVGs - most modern icon libraries use inline SVGs)
      'svg',
      
      // Font Awesome (all variants: solid, regular, light, duotone, brands, etc.)
      'i.fa', 'i.fas', 'i.far', 'i.fab', 'i.fal', 'i.fad', 'i.fass', 'i.fasr', 'i.fasl',
      'i[class^="fa-"]', 'i[class*=" fa-"]',
      
      // Bootstrap Icons
      'i.bi', 'i[class^="bi-"]', 'i[class*=" bi-"]',
      
      // Material Design Icons (community)
      'i.mdi', 'i[class^="mdi-"]', 'i[class*=" mdi-"]',
      
      // Google Material Icons & Symbols
      '.material-icons', '.material-icons-outlined', '.material-icons-round', 
      '.material-icons-sharp', '.material-icons-two-tone',
      '.material-symbols-outlined', '.material-symbols-rounded', '.material-symbols-sharp',
      
      // Ionicons
      'ion-icon', 'i[class^="ion-"]', 'i[class*=" ion-"]',
      
      // Glyphicons (Bootstrap 3)
      '.glyphicon',
      
      // Phosphor Icons
      'i.ph', 'i[class^="ph-"]', 'i[class*=" ph-"]',
      
      // Remix Icons
      'i[class^="ri-"]', 'i[class*=" ri-"]',
      
      // Line Awesome
      'i.la', 'i[class^="la-"]', 'i[class*=" la-"]',
      
      // Unicons
      'i.uil', 'i.uis', 'i.uim', 'i.uib',
      
      // Boxicons
      'i.bx', 'i[class^="bx-"]', 'i[class^="bxs-"]', 'i[class^="bxl-"]',
      
      // Octicons (GitHub)
      '.octicon',
      
      // Feather Icons (data attribute)
      '[data-feather]',
      
      // Lucide Icons (data attribute)
      '[data-lucide]',
      
      // css.gg
      'i[class^="gg-"]', 'i[class*=" gg-"]',
      
      // Tabler Icons
      'i.ti', 'i[class^="ti-"]', 'i[class*=" ti-"]',
      
      // Heroicons (usually SVG, but some implementations use classes)
      '[class*="heroicon"]',
      
      // Generic icon patterns (fallback for custom icon fonts like IcoMoon, Fontello)
      'i[class*="icon"]', 'i[class*="Icon"]',
      'span[class*="icon"]', 'span[class*="Icon"]',
      '[class*="icon-"]', '[class*="Icon-"]',
      '[class*="-icon"]', '[class*="-Icon"]',
      '[class*="ico-"]', '[class*="glyph"]',
      
      // Data attributes commonly used for icons
      '[data-icon]',
      
      // ARIA/accessibility patterns for icons
      'i[aria-hidden="true"]',
      '[role="img"]:not(img)',
    ];
    
    // Count elements by individual selector for better debugging
    for (const selector of selectors) {
      const count = document.querySelectorAll(selector).length;
      debugStats.elementsBySelector[selector] = count;
    }
    
    const elements = Array.from(document.querySelectorAll(selectors.join(', ')));
    debugStats.totalElementsFound = elements.length;
    
    console.log('[extractIcons] Starting extraction...');
    console.log('[extractIcons] Total elements found:', debugStats.totalElementsFound);
    console.log('[extractIcons] Elements by selector:', debugStats.elementsBySelector);
    
    // Inject stable identifiers into all icon elements
    let idCounter = 0;
    for (const el of elements) {
      const stableId = `ds-icon-${idCounter++}`;
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
        console.warn('[extractIcons] Rendering error:', error);
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
    
    // Calculate deduplication stats
    debugStats.uniqueIcons = imagesToStableIds.size;
    for (const [base64, ids] of imagesToStableIds.entries()) {
      const selectorCount = ids.size;
      debugStats.totalSnippets += selectorCount;
      debugStats.deduplicationMap[selectorCount] = (debugStats.deduplicationMap[selectorCount] || 0) + 1;
    }
    
    console.log('[extractIcons] Extraction complete');
    console.log('[extractIcons] Successfully rendered:', debugStats.successfullyRendered);
    console.log('[extractIcons] Unique icons:', debugStats.uniqueIcons);
    console.log('[extractIcons] Total selectors:', debugStats.totalSnippets);
    console.log('[extractIcons] Skipped (no glyph):', debugStats.skippedNoGlyph);
    console.log('[extractIcons] Skipped (svg zero size):', debugStats.skippedSvgZeroSize);
    console.log('[extractIcons] Rendering errors:', debugStats.renderingErrors);
    console.log('[extractIcons] Deduplication map:', debugStats.deduplicationMap);
    
    const results: IconResult[] = Array.from(imagesToStableIds.entries()).map(([base64, ids]) => ({
      base64,
      cssSelectors: Array.from(ids).map(id => `[data-ds-id="${id}"]`)
    }));
    
    // Return both debug stats and results
    return JSON.stringify({
      debug: debugStats,
      results: results
    });
  };

  return run();
})();


