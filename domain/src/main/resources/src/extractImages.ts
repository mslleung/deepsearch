(() => {
  type ImageResult = { base64: string; xPathSelectors: string[] };

  const getElementIndex = (el: Element): number => {
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

  const xPathSegmentFor = (el: Element): string => {
    const tag = el.tagName.toLowerCase();
    const index = getElementIndex(el);
    const parent = el.parentElement;
    const siblingsSameTag = parent 
      ? Array.from(parent.children).filter(ch => ch.tagName === el.tagName).length 
      : 1;
    if (siblingsSameTag > 1) {
      return `${tag}[${index}]`;
    }
    return tag;
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

  const run = async (): Promise<string> => {
    const imagesToXPathSelectors = new Map<string, Set<string>>();
    const images = Array.from(document.querySelectorAll('img'));
    
    for (const img of images) {
      const canvas = document.createElement('canvas');
      const ctx = canvas.getContext('2d');
      if (!ctx) continue;
      
      // Get displayed dimensions
      const rect = img.getBoundingClientRect();
      if (rect.width === 0 || rect.height === 0) continue;
      
      const width = Math.ceil(rect.width);
      const height = Math.ceil(rect.height);
      
      const scale = 2;
      canvas.width = Math.max(1, Math.floor(width * scale));
      canvas.height = Math.max(1, Math.floor(height * scale));
      ctx.scale(scale, scale);
      
      try {
        ctx.drawImage(img, 0, 0, width, height);
        const dataUrl = canvas.toDataURL('image/jpeg', 0.9);
        const base64 = dataUrl.replace(/^data:image\/jpeg;base64,/, '');
        
        const xPathSelector = uniqueXPathFor(img);
        if (!imagesToXPathSelectors.has(base64)) {
          imagesToXPathSelectors.set(base64, new Set());
        }
        imagesToXPathSelectors.get(base64)!.add(xPathSelector);
      } catch (e) {
        // Skip images that can't be drawn (CORS, etc.)
        continue;
      }
    }
    
    const results: ImageResult[] = Array.from(imagesToXPathSelectors.entries()).map(([base64, xPaths]) => ({
      base64,
      xPathSelectors: Array.from(xPaths)
    }));
    return JSON.stringify(results);
  };

  return run();
})();
