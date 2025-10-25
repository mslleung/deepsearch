(() => {
  type ImageResult = { base64: string; xPathSelectors: string[] };
  type FailedImage = { element: Element; xPath: string; reason: string };

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
    const failedImages: FailedImage[] = [];
    
    // Extract regular img elements
    const images = Array.from(document.querySelectorAll('img'));

    // Wait for all images to load
    await Promise.all(images.map(img => {
      if (img.complete) return Promise.resolve();
      return new Promise<void>((resolve) => {
        img.onload = () => resolve();
        img.onerror = () => resolve(); // Continue even if image fails to load
        // Timeout after 1 seconds
        setTimeout(() => resolve(), 1000);
      });
    }));

    console.log(`Found ${images.length} img elements to process`);
    
    // Process img elements in parallel
    const imgResults = await Promise.all(images.map(async (img) => {
      const canvas = document.createElement('canvas');
      const ctx = canvas.getContext('2d');
      if (!ctx) return null;
      
      // Get displayed dimensions
      const rect = img.getBoundingClientRect();
      const computedStyle = window.getComputedStyle(img);
      
      // Use natural dimensions if displayed dimensions are 0 but natural dimensions exist
      let width = Math.ceil(rect.width);
      let height = Math.ceil(rect.height);
      
      if ((width === 0 || height === 0) && img.naturalWidth > 0 && img.naturalHeight > 0) {
        width = img.naturalWidth;
        height = img.naturalHeight;
      }
      
      // Skip if still no valid dimensions
      if (width === 0 || height === 0) return null;
      
      const scale = 2;
      canvas.width = Math.max(1, Math.floor(width * scale));
      canvas.height = Math.max(1, Math.floor(height * scale));
      ctx.scale(scale, scale);
      
      try {
        // If the original image has a src, use it; otherwise try to get data URL
        let imageSrc = img.src;
        if (!imageSrc || imageSrc.startsWith('data:')) {
          // For data URLs or images without src, try to draw directly
          ctx.drawImage(img, 0, 0, width, height);
        } else {
          // For external images, use crossOrigin to avoid canvas taint
          const tempImg = new Image();
          tempImg.setAttribute('crossOrigin', 'anonymous');
          
          await new Promise<void>((resolve, reject) => {
            tempImg.onload = () => resolve();
            tempImg.onerror = () => reject(new Error('Failed to load image with crossOrigin'));
            tempImg.src = imageSrc;
            setTimeout(() => reject(new Error('Timeout loading image with crossOrigin')), 1000);
          });
          
          ctx.drawImage(tempImg, 0, 0, width, height);
        }
        
        const dataUrl = canvas.toDataURL('image/webp', 0.9);
        const base64 = dataUrl.replace(/^data:[^,]+,/, '');
        
        const xPathSelector = uniqueXPathFor(img);
        return { base64, xPathSelector, failed: null };
      } catch (e) {
        // Store failed images for fallback processing
        const errorMsg = e instanceof Error ? e.message : String(e);
        console.warn(`Failed to extract image at ${uniqueXPathFor(img)}: ${errorMsg}`);
        return {
          base64: null,
          xPathSelector: null,
          failed: {
            element: img,
            xPath: uniqueXPathFor(img),
            reason: errorMsg
          }
        };
      }
    }));
    
    // Aggregate results from parallel processing
    for (const result of imgResults) {
      if (!result) continue;
      
      if (result.failed) {
        failedImages.push(result.failed);
      } else if (result.base64 && result.xPathSelector) {
        if (!imagesToXPathSelectors.has(result.base64)) {
          imagesToXPathSelectors.set(result.base64, new Set());
        }
        imagesToXPathSelectors.get(result.base64)!.add(result.xPathSelector);
      }
    }
    
    // Extract background images from elements
    const elementsWithBackgrounds = Array.from(document.querySelectorAll('*')).filter(el => {
      const style = window.getComputedStyle(el);
      const backgroundImage = style.backgroundImage;
      return backgroundImage && backgroundImage !== 'none' && backgroundImage.startsWith('url(');
    });
    
    console.log(`Found ${elementsWithBackgrounds.length} elements with backgrounds to process`);
    
    // Process background images in parallel
    const bgResults = await Promise.all(elementsWithBackgrounds.map(async (element) => {
      const canvas = document.createElement('canvas');
      const ctx = canvas.getContext('2d');
      if (!ctx) return null;
      
      const rect = element.getBoundingClientRect();
      const computedStyle = window.getComputedStyle(element);
      
      // Skip elements that are completely hidden
      if (computedStyle.display === 'none' || computedStyle.visibility === 'hidden' || computedStyle.opacity === '0') {
        return null;
      }
      
      // Skip if no valid dimensions
      if (rect.width === 0 || rect.height === 0) return null;
      
      const width = Math.ceil(rect.width);
      const height = Math.ceil(rect.height);
      
      const scale = 2;
      canvas.width = Math.max(1, Math.floor(width * scale));
      canvas.height = Math.max(1, Math.floor(height * scale));
      ctx.scale(scale, scale);
      
      try {
        // Draw the element's background image
        const backgroundImage = computedStyle.backgroundImage;
        const urlMatch = backgroundImage.match(/url\(['"]?([^'"]+)['"]?\)/);
        if (urlMatch) {
          const imageUrl = urlMatch[1];
          
          // Use crossOrigin to avoid canvas taint
          const tempImg = new Image();
          tempImg.setAttribute('crossOrigin', 'anonymous');
          
          await new Promise<void>((resolve, reject) => {
            tempImg.onload = () => resolve();
            tempImg.onerror = () => reject(new Error('Failed to load background image with crossOrigin'));
            tempImg.src = imageUrl;
            setTimeout(() => reject(new Error('Timeout loading background image with crossOrigin')), 1000);
          });
          
          ctx.drawImage(tempImg, 0, 0, width, height);
          
          const dataUrl = canvas.toDataURL('image/webp', 0.9);
          const base64 = dataUrl.replace(/^data:[^,]+,/, '');
          
          const xPathSelector = uniqueXPathFor(element);
          return { base64, xPathSelector, failed: null };
        }
        return null;
      } catch (e) {
        // Store failed background images for fallback processing
        const errorMsg = e instanceof Error ? e.message : String(e);
        console.warn(`Failed to extract background image at ${uniqueXPathFor(element)}: ${errorMsg}`);
        return {
          base64: null,
          xPathSelector: null,
          failed: {
            element: element,
            xPath: uniqueXPathFor(element),
            reason: errorMsg
          }
        };
      }
    }));
    
    // Aggregate results from parallel background processing
    for (const result of bgResults) {
      if (!result) continue;
      
      if (result.failed) {
        failedImages.push(result.failed);
      } else if (result.base64 && result.xPathSelector) {
        if (!imagesToXPathSelectors.has(result.base64)) {
          imagesToXPathSelectors.set(result.base64, new Set());
        }
        imagesToXPathSelectors.get(result.base64)!.add(result.xPathSelector);
      }
    }
    
    const results: ImageResult[] = Array.from(imagesToXPathSelectors.entries()).map(([base64, xPaths]) => ({
      base64,
      xPathSelectors: Array.from(xPaths)
    }));
    
    // Return both successful results and failed images for fallback processing
    return JSON.stringify({
      successful: results,
      failed: failedImages.map(f => ({ xPath: f.xPath, reason: f.reason }))
    });
  };

  return run();
})();
