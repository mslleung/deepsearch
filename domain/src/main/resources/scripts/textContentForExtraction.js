(tableXPaths) => {
  // Set to store processed elements to avoid duplicates
  const processedElements = new Set();

  // Process identified tables: add their HTML and mark as processed
  let tableHtml = '';
  if (tableXPaths && tableXPaths.length > 0) {
    tableXPaths.forEach(xpath => {
      try {
        const result = document.evaluate(xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null);
        const tableEl = result.singleNodeValue;
        if (tableEl) {
          tableHtml += tableEl.outerHTML + '\n\n';
          // Mark the table root and all descendants as processed
          processedElements.add(tableEl);
          const walker = document.createTreeWalker(tableEl, NodeFilter.SHOW_ELEMENT, null, false);
          let child;
          while (child = walker.nextNode()) {
            processedElements.add(child);
          }
        }
      } catch (e) {
        console.warn('Invalid XPath:', xpath, e);
      }
    });
  }

  // Finally, get all other text content, excluding text from processed table elements
  let otherText = '';
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
  let node;
  while (node = walker.nextNode()) {
    if (node.parentElement && !processedElements.has(node.parentElement) && node.textContent.trim()) {
      // Check if any ancestor is a processed element
      let parent = node.parentElement;
      let isProcessed = false;
      while (parent && parent !== document.body) {
        if (processedElements.has(parent)) {
          isProcessed = true;
          break;
        }
        parent = parent.parentElement;
      }
      if (!isProcessed) {
        otherText += node.textContent.trim() + '\n';
      }
    }
  }

  // A simple way to remove duplicate lines that might arise from nested elements
  const uniqueLines = [...new Set(otherText.split('\n'))];
  otherText = uniqueLines.join('\n');

  return tableHtml + otherText;
}
