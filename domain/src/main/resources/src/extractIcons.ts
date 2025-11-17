(() => {
  type IconResult = { base64: string; xPathSelectors: string[] };

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
    totalXPaths: number;
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
        classes: el.className,
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

    const dataUrl = canvas.toDataURL('image/webp', 1.0);
    const base64 = dataUrl.replace(/^data:[^,]+,/, '');
    return base64;
  };

  const xPathSegmentFor = (el: Element): string => {
    const tag = el.tagName.toLowerCase();
    
    // 1. Check for our injected unique ID first (for icon elements)
    const tempId = el.getAttribute('data-ds-temp-icon-id');
    if (tempId) {
      return `${tag}[@data-ds-temp-icon-id='${tempId}']`;
    }
    
    // 2. Try to use id attribute (most stable)
    const id = el.getAttribute('id');
    if (id && id.trim()) {
      return `${tag}[@id='${id.trim().replace(/'/g, "\\'")}']`;
    }
    
    // 3. Try using class attribute if it's reasonably unique
    const classes = el.getAttribute('class');
    if (classes && classes.trim()) {
      const classList = classes.trim().split(/\s+/).filter(c => c.length > 0);
      if (classList.length > 0) {
        // Use contains for each class to be more resilient
        const classConditions = classList.map(c => `contains(@class,'${c.replace(/'/g, "\\'")}')`).join(' and ');
        return `${tag}[${classConditions}]`;
      }
    }
    
    // 4. For other potentially unique attributes
    const role = el.getAttribute('role');
    if (role && role.trim()) {
      return `${tag}[@role='${role.trim().replace(/'/g, "\\'")}']`;
    }
    
    const ariaLabel = el.getAttribute('aria-label');
    if (ariaLabel && ariaLabel.trim()) {
      return `${tag}[@aria-label='${ariaLabel.trim().replace(/'/g, "\\'")}']`;
    }
    
    const href = el.getAttribute('href');
    if (href && href.trim()) {
      return `${tag}[@href='${href.trim().replace(/'/g, "\\'")}']`;
    }
    
    // 5. Try data attributes
    const dataAttrs = Array.from(el.attributes).filter(attr => attr.name.startsWith('data-'));
    if (dataAttrs.length > 0) {
      const attr = dataAttrs[0];
      return `${tag}[@${attr.name}='${attr.value.replace(/'/g, "\\'")}']`;
    }
    
    // 6. Last resort: inject a temporary unique data attribute
    const uniqueId = `ds-${Math.random().toString(36).substr(2, 9)}`;
    el.setAttribute('data-ds-temp-id', uniqueId);
    return `${tag}[@data-ds-temp-id='${uniqueId}']`;
  };

  const uniqueXPathFor = (el: Element): string => {
    const segments: string[] = [];
    let node: Element | null = el;
    
    while (node && node.nodeType === Node.ELEMENT_NODE && node.tagName.toLowerCase() !== 'html') {
      const segment = xPathSegmentFor(node);
      segments.unshift(segment);
      node = node.parentElement;
    }
    
    return '//' + segments.join('/');
  };

  const renderSvgIcon = async (el: Element, debugStats: DebugStats): Promise<string | null> => {
    const svg = el as SVGElement;
    
    // Get computed dimensions
    const bbox = svg.getBoundingClientRect();
    if (bbox.width === 0 || bbox.height === 0) {
      debugStats.skippedSvgZeroSize++;
      debugStats.skippedDetails.push({
        tag: 'svg',
        classes: el.className,
        width: bbox.width,
        height: bbox.height,
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
    const width = Math.ceil(bbox.width);
    const height = Math.ceil(bbox.height);
    svgClone.setAttribute('width', width.toString());
    svgClone.setAttribute('height', height.toString());
    
    // Serialize SVG to data URL
    const svgString = new XMLSerializer().serializeToString(svgClone);
    const svgBlob = new Blob([svgString], { type: 'image/svg+xml;charset=utf-8' });
    
    // Convert to canvas for JPEG output
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
        
        const dataUrl = canvas.toDataURL('image/webp', 1.0);
        const base64 = dataUrl.replace(/^data:[^,]+,/, '');
        resolve(base64);
      };
      img.onerror = () => resolve(null);
      img.src = URL.createObjectURL(svgBlob);
    });
  };

  const run = async (): Promise<string> => {
    const imagesToXPathSelectors = new Map<string, Set<string>>();
    
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
      totalXPaths: 0,
      skippedDetails: [],
      deduplicationMap: {} // maps number of xPaths to count of icons with that many xPaths
    };
    
    // Query for multiple icon types:
    // 1. <i> tags (Font Awesome, Bootstrap Icons, etc.)
    // 2. <svg> tags (inline SVG icons)
    // 3. Elements with common icon class patterns
    // 4. <span> with icon fonts or classes
    const selectors = [
      'i',
      'svg',
      'svg[class*="icon"]',
      'svg[class*="Icon"]',
      '[class*="icon-"]',
      '[class*="Icon-"]',
      'span[class*="icon"]',
      'span[class*="Icon"]',
      '[role="img"]'
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
    
    // Inject unique IDs for all icon elements upfront to ensure unique xPaths
    elements.forEach((el, index) => {
      const uniqueId = `icon-${index}-${Math.random().toString(36).substr(2, 9)}`;
      el.setAttribute('data-ds-temp-icon-id', uniqueId);
    });
    
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
          classes: el.className,
          error: String(error),
          reason: 'rendering_error'
        });
        console.warn('[extractIcons] Rendering error:', error);
        continue;
      }
      
      if (!rendered) continue;
      
      debugStats.successfullyRendered++;
      
      const xPathSelector = uniqueXPathFor(el);
      if (!imagesToXPathSelectors.has(rendered)) {
        imagesToXPathSelectors.set(rendered, new Set());
      }
      imagesToXPathSelectors.get(rendered)!.add(xPathSelector);
    }
    
    // Calculate deduplication stats
    debugStats.uniqueIcons = imagesToXPathSelectors.size;
    for (const [base64, xPaths] of imagesToXPathSelectors.entries()) {
      const xPathCount = xPaths.size;
      debugStats.totalXPaths += xPathCount;
      debugStats.deduplicationMap[xPathCount] = (debugStats.deduplicationMap[xPathCount] || 0) + 1;
    }
    
    console.log('[extractIcons] Extraction complete');
    console.log('[extractIcons] Successfully rendered:', debugStats.successfullyRendered);
    console.log('[extractIcons] Unique icons:', debugStats.uniqueIcons);
    console.log('[extractIcons] Total xPaths:', debugStats.totalXPaths);
    console.log('[extractIcons] Skipped (no glyph):', debugStats.skippedNoGlyph);
    console.log('[extractIcons] Skipped (svg zero size):', debugStats.skippedSvgZeroSize);
    console.log('[extractIcons] Rendering errors:', debugStats.renderingErrors);
    console.log('[extractIcons] Deduplication map:', debugStats.deduplicationMap);
    
    const results: IconResult[] = Array.from(imagesToXPathSelectors.entries()).map(([base64, xPaths]) => ({
      base64,
      xPathSelectors: Array.from(xPaths)
    }));
    
    // Return both debug stats and results
    return JSON.stringify({
      debug: debugStats,
      results: results
    });
  };

  return run();
})();


