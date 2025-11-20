(() => {
  type ImageResult = { base64: string; xPathSelectors: string[] };
  type FailedImage = { element: Element; xPath: string; reason: string };

  const xPathSegmentFor = (el: Element): string => {
    const tag = el.tagName.toLowerCase();
    
    // 1. Check for our injected unique ID first (for image elements)
    const tempId = el.getAttribute('data-ds-temp-img-id');
    if (tempId) {
      return `${tag}[@data-ds-temp-img-id='${tempId}']`;
    }
    
    // 2. Try to use id attribute (most stable)
    const id = el.getAttribute('id');
    if (id && id.trim()) {
      return `${tag}[@id='${id.trim().replace(/'/g, "\\'")}']`;
    }
    
    // 3. For img elements, try using src attribute
    if (tag === 'img') {
      const src = el.getAttribute('src');
      if (src && src.trim()) {
        // Escape single quotes in XPath
        return `${tag}[@src='${src.trim().replace(/'/g, "\\'")}']`;
      }
    }
    
    // 4. Try using class attribute if it's reasonably unique
    const classes = el.getAttribute('class');
    if (classes && classes.trim()) {
      const classList = classes.trim().split(/\s+/).filter(c => c.length > 0);
      if (classList.length > 0) {
        // Use contains for each class to be more resilient
        const classConditions = classList.map(c => `contains(@class,'${c.replace(/'/g, "\\'")}')`).join(' and ');
        return `${tag}[${classConditions}]`;
      }
    }
    
    // 5. For other potentially unique attributes
    const alt = el.getAttribute('alt');
    if (alt && alt.trim()) {
      return `${tag}[@alt='${alt.trim().replace(/'/g, "\\'")}']`;
    }
    
    const href = el.getAttribute('href');
    if (href && href.trim()) {
      return `${tag}[@href='${href.trim().replace(/'/g, "\\'")}']`;
    }
    
    // 6. Try data attributes
    const dataAttrs = Array.from(el.attributes).filter(attr => attr.name.startsWith('data-'));
    if (dataAttrs.length > 0) {
      const attr = dataAttrs[0];
      return `${tag}[@${attr.name}='${attr.value.replace(/'/g, "\\'")}']`;
    }
    
    // 7. Last resort: inject a temporary unique data attribute
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

  const run = async (): Promise<string> => {
    const imagesToXPathSelectors = new Map<string, Set<string>>();
    const failedImages: FailedImage[] = [];
    
    // Extract regular img elements
    const images = Array.from(document.querySelectorAll('img'));
    
    // Inject unique IDs for all img elements upfront to ensure unique xPaths
    images.forEach((img, index) => {
      const uniqueId = `img-${index}-${Math.random().toString(36).substr(2, 9)}`;
      img.setAttribute('data-ds-temp-img-id', uniqueId);
    });

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
      
      const scale = 1;
      canvas.width = Math.max(1, Math.floor(width * scale));
      canvas.height = Math.max(1, Math.floor(height * scale));
      ctx.scale(scale, scale);
      
      try {
        // Optimistically try to use the already-loaded image first
        ctx.drawImage(img, 0, 0, width, height);
        
        // Test if we can extract the data (this will throw SecurityError if tainted)
        const dataUrl = canvas.toDataURL('image/webp', 0.9);
        const base64 = dataUrl.replace(/^data:[^,]+,/, '');
        
        const xPathSelector = uniqueXPathFor(img);
        return { base64, xPathSelector, failed: null };
      } catch (e) {
        // Canvas is tainted or drawing failed - try re-downloading with CORS
        if (e instanceof DOMException && e.name === 'SecurityError' && img.src && !img.src.startsWith('data:')) {
          try {
            // Clear the tainted canvas
            ctx.clearRect(0, 0, width, height);
            
            // Re-download with crossOrigin to avoid canvas taint
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
            
            const xPathSelector = uniqueXPathFor(img);
            return { base64, xPathSelector, failed: null };
          } catch (retryError) {
            // Re-download also failed
            const errorMsg = retryError instanceof Error ? retryError.message : String(retryError);
            console.warn(`Failed to extract image at ${uniqueXPathFor(img)} after retry: ${errorMsg}`);
            return {
              base64: null,
              xPathSelector: null,
              failed: {
                element: img,
                xPath: uniqueXPathFor(img),
                reason: `CORS retry failed: ${errorMsg}`
              }
            };
          }
        }
        
        // Some other error (not SecurityError or not recoverable) - report it
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
    
    // Inject unique IDs for all background image elements upfront to ensure unique xPaths
    elementsWithBackgrounds.forEach((el, index) => {
      const uniqueId = `bg-${index}-${Math.random().toString(36).substr(2, 9)}`;
      el.setAttribute('data-ds-temp-img-id', uniqueId);
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
      
      const scale = 1;
      canvas.width = Math.max(1, Math.floor(width * scale));
      canvas.height = Math.max(1, Math.floor(height * scale));
      ctx.scale(scale, scale);
      
      try {
        // Extract the background image URL
        const backgroundImage = computedStyle.backgroundImage;
        const urlMatch = backgroundImage.match(/url\(['"]?([^'"]+)['"]?\)/);
        if (!urlMatch) return null;
        
        const imageUrl = urlMatch[1];
        
        // Create a temporary image to render the background
        const tempImg = new Image();
        
        // Try loading without crossOrigin first (optimistic approach)
        await new Promise<void>((resolve, reject) => {
          tempImg.onload = () => resolve();
          tempImg.onerror = () => reject(new Error('Failed to load background image'));
          tempImg.src = imageUrl;
          setTimeout(() => reject(new Error('Timeout loading background image')), 1000);
        });
        
        // Optimistically try to draw and extract
        ctx.drawImage(tempImg, 0, 0, width, height);
        
        try {
          // Test if we can extract the data (this will throw SecurityError if tainted)
          const dataUrl = canvas.toDataURL('image/webp', 0.9);
          const base64 = dataUrl.replace(/^data:[^,]+,/, '');
          
          const xPathSelector = uniqueXPathFor(element);
          return { base64, xPathSelector, failed: null };
        } catch (securityError) {
          // Canvas is tainted - retry with crossOrigin
          if (securityError instanceof DOMException && securityError.name === 'SecurityError') {
            // Clear the tainted canvas
            ctx.clearRect(0, 0, width, height);
            
            // Re-download with crossOrigin to avoid canvas taint
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
            
            const xPathSelector = uniqueXPathFor(element);
            return { base64, xPathSelector, failed: null };
          }
          throw securityError;
        }
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
