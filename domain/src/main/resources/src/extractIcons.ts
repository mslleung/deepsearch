(() => {
  type IconResult = { base64: string; selectors: string[] };

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

  const renderIcon = async (el: Element): Promise<string | null> => {
    const style = window.getComputedStyle(el as HTMLElement);
    const before = window.getComputedStyle(el as HTMLElement, '::before');
    const glyph = toPlainString(before.content) || (el.textContent || '').trim();
    if (!glyph) return null;

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

    const scale = 2;
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

    const dataUrl = canvas.toDataURL('image/jpeg', 0.9);
    const base64 = dataUrl.replace(/^data:image\/jpeg;base64,/, '');
    return base64;
  };

  const isUniqueId = (id: string): boolean => {
    if (!id) return false;
    try {
      const match = document.querySelectorAll(`#${CSS.escape(id)}`);
      return match.length === 1;
    } catch (_e) {
      return false;
    }
  };

  const cssSegmentFor = (el: Element): string => {
    const tag = el.tagName.toLowerCase();
    const id = (el as HTMLElement).id;
    if (id && isUniqueId(id)) {
      return `${tag}#${CSS.escape(id)}`;
    }
    const classList = Array.from((el as HTMLElement).classList || []);
    const classSegment = classList.length > 0 ? `.${classList.map(c => CSS.escape(c)).join('.')}` : '';

    // nth-of-type for disambiguation among siblings of same tag
    let nth = 1;
    let sibling = el.previousElementSibling;
    while (sibling) {
      if (sibling.tagName === el.tagName) nth++;
      sibling = sibling.previousElementSibling;
    }
    const siblingsSameTag = el.parentElement ? Array.from(el.parentElement.children).filter(ch => ch.tagName === el.tagName).length : 1;
    const nthSegment = siblingsSameTag > 1 ? `:nth-of-type(${nth})` : '';
    return `${tag}${classSegment}${nthSegment}`;
  };

  const uniqueSelectorFor = (el: Element): string => {
    const segments: string[] = [];
    let node: Element | null = el;
    while (node && node.nodeType === Node.ELEMENT_NODE && node.tagName.toLowerCase() !== 'html') {
      const seg = cssSegmentFor(node);
      segments.unshift(seg);
      // If this segment has a unique id, we can stop climbing
      const id = (node as HTMLElement).id;
      if (id && isUniqueId(id)) break;
      node = node.parentElement;
    }
    return segments.join(' > ');
  };

  const run = async (): Promise<string> => {
    const imagesToSelectors = new Map<string, Set<string>>();
    const elements = Array.from(document.querySelectorAll('i'));
    for (const el of elements) {
      const rendered = await renderIcon(el);
      if (!rendered) continue;
      const selector = uniqueSelectorFor(el);
      if (!imagesToSelectors.has(rendered)) {
        imagesToSelectors.set(rendered, new Set());
      }
      imagesToSelectors.get(rendered)!.add(selector);
    }
    const results: IconResult[] = Array.from(imagesToSelectors.entries()).map(([base64, sels]) => ({
      base64,
      selectors: Array.from(sels)
    }));
    return JSON.stringify(results);
  };

  return run();
})();


