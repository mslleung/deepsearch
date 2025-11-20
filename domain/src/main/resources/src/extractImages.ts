(() => {
  type ImageResult = { base64: string; cssSelectors: string[] };
  type FailedImage = { element: Element; cssSelector: string; reason: string };

  const extractStableId = (el: Element): string | null => {
    return (el as HTMLElement).getAttribute('data-ds-id');
  };

  const run = async (): Promise<string> => {
    const imagesToStableIds = new Map<string, Set<string>>();
    const failedImages: FailedImage[] = [];
    
    // Extract regular img elements
    const images = Array.from(document.querySelectorAll('img'));

    // Inject stable identifiers into img elements
    let idCounter = 0;
    images.forEach(img => {
      img.setAttribute('data-ds-id', `ds-image-${idCounter++}`);
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
        
        const stableId = extractStableId(img);
        return { base64, stableId, failed: null };
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
            
            const stableId = extractStableId(img);
            return { base64, stableId, failed: null };
          } catch (retryError) {
            // Re-download also failed
            const errorMsg = retryError instanceof Error ? retryError.message : String(retryError);
            const stableId = extractStableId(img);
            const cssSelector = stableId ? `[data-ds-id="${stableId}"]` : '';
            console.warn(`Failed to extract image at ${cssSelector} after retry: ${errorMsg}`);
            return {
              base64: null,
              stableId: null,
              failed: {
                element: img,
                cssSelector: cssSelector,
                reason: `CORS retry failed: ${errorMsg}`
              }
            };
          }
        }
        
        // Some other error (not SecurityError or not recoverable) - report it
        const errorMsg = e instanceof Error ? e.message : String(e);
        const stableId = extractStableId(img);
        const cssSelector = stableId ? `[data-ds-id="${stableId}"]` : '';
        console.warn(`Failed to extract image at ${cssSelector}: ${errorMsg}`);
        return {
          base64: null,
          stableId: null,
          failed: {
            element: img,
            cssSelector: cssSelector,
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
      } else if (result.base64 && result.stableId) {
        if (!imagesToStableIds.has(result.base64)) {
          imagesToStableIds.set(result.base64, new Set());
        }
        imagesToStableIds.get(result.base64)!.add(result.stableId);
      }
    }
    
    // Extract background images from elements
    const elementsWithBackgrounds = Array.from(document.querySelectorAll('*')).filter(el => {
      const style = window.getComputedStyle(el);
      const backgroundImage = style.backgroundImage;
      return backgroundImage && backgroundImage !== 'none' && backgroundImage.startsWith('url(');
    });
    
    // Inject stable identifiers into elements with background images
    elementsWithBackgrounds.forEach(element => {
      (element as HTMLElement).setAttribute('data-ds-id', `ds-image-${idCounter++}`);
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
          
          const stableId = extractStableId(element);
          return { base64, stableId, failed: null };
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
            
            const stableId = extractStableId(element);
            return { base64, stableId, failed: null };
          }
          throw securityError;
        }
      } catch (e) {
        // Store failed background images for fallback processing
        const errorMsg = e instanceof Error ? e.message : String(e);
        const stableId = extractStableId(element);
        const cssSelector = stableId ? `[data-ds-id="${stableId}"]` : '';
        console.warn(`Failed to extract background image at ${cssSelector}: ${errorMsg}`);
        return {
          base64: null,
          stableId: null,
          failed: {
            element: element,
            cssSelector: cssSelector,
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
    
    // Return both successful results and failed images for fallback processing
    return JSON.stringify({
      successful: results,
      failed: failedImages.map(f => ({ cssSelector: f.cssSelector, reason: f.reason }))
    });
  };

  return run();
})();
