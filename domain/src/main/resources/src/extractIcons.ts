(() => {
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
    const fill = style.color || 'rgb(0,0,0)';

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
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, width, height);
    ctx.fillStyle = fill;
    ctx.font = font;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(glyph, width / 2, height / 2);

    const dataUrl = canvas.toDataURL('image/jpeg', 0.9);
    const base64 = dataUrl.replace(/^data:image\/jpeg;base64,/, '');
    return base64;
  };

  const run = async (): Promise<string> => {
    const icons: string[] = [];
    const seen = new Set<string>();
    const elements = Array.from(document.querySelectorAll('i'));
    for (const el of elements) {
      const rendered = await renderIcon(el);
      if (rendered && !seen.has(rendered)) {
        seen.add(rendered);
        icons.push(rendered);
      }
    }
    return JSON.stringify(icons);
  };

  return run();
})();


