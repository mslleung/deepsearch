"use strict";
(() => {
    const toPlainString = (cssContent) => {
        if (!cssContent || cssContent === 'none')
            return '';
        let s = String(cssContent).trim();
        if ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith('\'') && s.endsWith('\''))) {
            s = s.slice(1, -1);
        }
        if (/^\\[0-9a-fA-F]{1,6}$/.test(s)) {
            const hex = s.replace('\\', '');
            try {
                const codePoint = parseInt(hex, 16);
                return String.fromCodePoint(codePoint);
            }
            catch (_e) {
                return '';
            }
        }
        return s;
    };
    const getContrastingBackground = (color) => {
        // Parse color to RGB values
        const div = document.createElement('div');
        div.style.color = color;
        document.body.appendChild(div);
        const computedColor = window.getComputedStyle(div).color;
        document.body.removeChild(div);
        // Extract RGB values from computed color (format: rgb(r, g, b) or rgba(r, g, b, a))
        const rgbMatch = computedColor.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/);
        if (!rgbMatch)
            return '#f0f0f0'; // Default light gray background
        const r = parseInt(rgbMatch[1]);
        const g = parseInt(rgbMatch[2]);
        const b = parseInt(rgbMatch[3]);
        // Calculate luminance using relative luminance formula
        const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
        // If the color is light (luminance > 0.5), use dark background, otherwise use light background
        return luminance > 0.5 ? '#2a2a2a' : '#f0f0f0';
    };
    const renderIcon = async (el) => {
        const style = window.getComputedStyle(el);
        const before = window.getComputedStyle(el, '::before');
        const glyph = toPlainString(before.content) || (el.textContent || '').trim();
        if (!glyph)
            return null;
        await document.fonts.ready;
        const font = style.font ||
            `${style.fontStyle || 'normal'} ${style.fontVariant || 'normal'} ${style.fontWeight || '400'} ${style.fontSize || '16px'} / ${style.lineHeight || 'normal'} ${style.fontFamily || 'sans-serif'}`;
        const fontSizePx = parseFloat(style.fontSize || '16');
        const fill = style.color;
        const measureCanvas = document.createElement('canvas');
        const measureCtx = measureCanvas.getContext('2d');
        if (!measureCtx)
            return null;
        measureCtx.font = font;
        const metrics = measureCtx.measureText(glyph);
        const width = Math.ceil(Math.max(metrics.width, fontSizePx) * 1.5);
        const height = Math.ceil(fontSizePx * 1.8);
        const scale = 2;
        const canvas = document.createElement('canvas');
        canvas.width = Math.max(1, Math.floor(width * scale));
        canvas.height = Math.max(1, Math.floor(height * scale));
        const ctx = canvas.getContext('2d');
        if (!ctx)
            return null;
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
    const getElementIndex = (el) => {
        let index = 1;
        let sibling = el.previousElementSibling;
        while (sibling) {
            if (sibling.tagName === el.tagName) {
                index++;
            }
            sibling = sibling.previousElementSibling;
        }
        return index;
    };
    const xPathSegmentFor = (el) => {
        const tag = el.tagName.toLowerCase();
        const index = getElementIndex(el);
        // Count total siblings with same tag
        const parent = el.parentElement;
        const siblingsSameTag = parent
            ? Array.from(parent.children).filter(ch => ch.tagName === el.tagName).length
            : 1;
        // Only add index predicate if there are multiple siblings with same tag
        if (siblingsSameTag > 1) {
            return `${tag}[${index}]`;
        }
        return tag;
    };
    const uniqueXPathFor = (el) => {
        const segments = [];
        let node = el;
        while (node && node.nodeType === Node.ELEMENT_NODE && node.tagName.toLowerCase() !== 'html') {
            const segment = xPathSegmentFor(node);
            segments.unshift(segment);
            node = node.parentElement;
        }
        return '//' + segments.join('/');
    };
    const renderSvgIcon = async (el) => {
        const svg = el;
        // Get computed dimensions
        const bbox = svg.getBoundingClientRect();
        if (bbox.width === 0 || bbox.height === 0)
            return null;
        // Get the computed color
        const style = window.getComputedStyle(svg);
        const fill = style.color || style.fill || '#000000';
        // Clone the SVG to avoid modifying the original
        const svgClone = svg.cloneNode(true);
        // Set explicit width/height for rendering
        const width = Math.ceil(bbox.width);
        const height = Math.ceil(bbox.height);
        svgClone.setAttribute('width', width.toString());
        svgClone.setAttribute('height', height.toString());
        // Serialize SVG to data URL
        const svgString = new XMLSerializer().serializeToString(svgClone);
        const svgBlob = new Blob([svgString], { type: 'image/svg+xml;charset=utf-8' });
        // Convert to canvas for JPEG output
        return new Promise((resolve) => {
            const img = new Image();
            img.onload = () => {
                const scale = 2;
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
                const dataUrl = canvas.toDataURL('image/jpeg', 0.9);
                const base64 = dataUrl.replace(/^data:image\/jpeg;base64,/, '');
                resolve(base64);
            };
            img.onerror = () => resolve(null);
            img.src = URL.createObjectURL(svgBlob);
        });
    };
    const run = async () => {
        const imagesToXPathSelectors = new Map();
        // Query for multiple icon types:
        // 1. <i> tags (Font Awesome, Bootstrap Icons, etc.)
        // 2. <svg> tags (inline SVG icons)
        // 3. Elements with common icon class patterns
        // 4. <span> with icon fonts or classes
        const selectors = [
            'i',
            'svg[class*="icon"]',
            'svg[class*="Icon"]',
            '[class*="icon-"]',
            '[class*="Icon-"]',
            'span[class*="icon"]',
            'span[class*="Icon"]',
            '[role="img"]'
        ];
        const elements = Array.from(document.querySelectorAll(selectors.join(', ')));
        for (const el of elements) {
            let rendered = null;
            if (el.tagName.toLowerCase() === 'svg') {
                rendered = await renderSvgIcon(el);
            }
            else {
                rendered = await renderIcon(el);
            }
            if (!rendered)
                continue;
            const xPathSelector = uniqueXPathFor(el);
            if (!imagesToXPathSelectors.has(rendered)) {
                imagesToXPathSelectors.set(rendered, new Set());
            }
            imagesToXPathSelectors.get(rendered).add(xPathSelector);
        }
        const results = Array.from(imagesToXPathSelectors.entries()).map(([base64, xPaths]) => ({
            base64,
            xPathSelectors: Array.from(xPaths)
        }));
        return JSON.stringify(results);
    };
    return run();
})();
