() => {
  // Set to store processed elements to avoid duplicates
  const processedElements = new Set();

  /**
   * Converts a 2D array representing a table into a Markdown table string.
   * @param {string[][]} tableData - The 2D array of table data.
   * @returns {string} The Markdown table.
   */
  function toMarkdownTable(tableData) {
    if (!tableData || tableData.length === 0) {
      return '';
    }

    const header = tableData[0];
    const alignment = header.map(() => '---').join(' | ');
    const headerLine = `| ${header.join(' | ')} |`;
    const separatorLine = `| ${alignment} |`;

    const body = tableData.slice(1).map(row => {
      const sanitizedRow = row.map(cell => (cell || '').replace(/\|/g, '\\|'));
      return `| ${sanitizedRow.join(' | ')} |`;
    }).join('\n');

    return `${headerLine}\n${separatorLine}\n${body}\n\n`;
  }

  /**
   * Parses a standard HTML <table> element into a 2D array.
   * Handles colspan and rowspan.
   * @param {HTMLTableElement} tableElement - The table element to parse.
   * @returns {string[][]} A 2D array representing the table.
   */
  function parseHtmlTable(tableElement) {
    const table = [];
    const rows = Array.from(tableElement.rows);

    rows.forEach((row, rowIndex) => {
      if (!table[rowIndex]) {
        table[rowIndex] = [];
      }
      let colIndex = 0;
      Array.from(row.cells).forEach(cell => {
        while (table[rowIndex][colIndex]) {
          colIndex++;
        }

        const text = cell.textContent.trim();
        const colspan = cell.colSpan || 1;
        const rowspan = cell.rowSpan || 1;

        for (let i = 0; i < rowspan; i++) {
          for (let j = 0; j < colspan; j++) {
            const R = rowIndex + i;
            const C = colIndex + j;
            if (!table[R]) {
              table[R] = [];
            }
            // Fill subsequent cells with the same text for markdown
            table[R][C] = text;
          }
        }
        colIndex += colspan;
        processedElements.add(cell);
      });
    });
    processedElements.add(tableElement);
    return table;
  }

  /**
   * Parses an ARIA role-based table into a 2D array.
   * @param {HTMLElement} ariaTableElement - The element with role="table".
   * @returns {string[][]} A 2D array representing the table.
   */
  function parseAriaTable(ariaTableElement) {
      const table = [];
      const rows = Array.from(ariaTableElement.querySelectorAll('[role="row"]'));

      rows.forEach((row, rowIndex) => {
          table[rowIndex] = [];
          const cells = Array.from(row.querySelectorAll('[role="cell"], [role="gridcell"], [role="columnheader"], [role="rowheader"]'));
          cells.forEach((cell, colIndex) => {
              // Basic parsing, assuming no complex spans for ARIA for now.
              table[rowIndex][colIndex] = cell.textContent.trim();
              processedElements.add(cell);
          });
      });
      processedElements.add(ariaTableElement);
      return table;
  }


  /**
   * Heuristically finds and parses tables made from divs/other elements.
   * @returns {string[][][]} An array of 2D arrays representing found tables.
   */
  function parseVisualTables() {
      const allTables = [];
      // 1. Identify Candidate Nodes
      const candidates = Array.from(document.querySelectorAll('body *')).filter(el => {
          return el.offsetParent !== null && // is visible
              !processedElements.has(el) &&
              Array.from(el.children).every(child => ['BR', 'SPAN', 'P', 'EM', 'I', 'B', 'STRONG'].includes(child.tagName) || window.getComputedStyle(child).display !== 'block') &&
              el.textContent.trim().length > 0 &&
              !['SCRIPT', 'STYLE', 'NOSCRIPT', 'HEAD', 'META', 'TITLE'].includes(el.tagName);
      }).map(el => ({
          el,
          rect: el.getBoundingClientRect(),
          text: el.textContent.trim()
      }));

      if (candidates.length < 3) return []; // Not enough elements to form a table

      // 2. Group nodes into rows
      const yTolerance = 10; // pixels
      candidates.sort((a, b) => a.rect.top - b.rect.top);
      const rows = [];
      let currentRow = [candidates[0]];
      for (let i = 1; i < candidates.length; i++) {
          if (Math.abs(candidates[i].rect.top - currentRow[0].rect.top) < yTolerance) {
              currentRow.push(candidates[i]);
          } else {
              rows.push(currentRow.sort((a, b) => a.rect.left - b.rect.left));
              currentRow = [candidates[i]];
          }
      }
      rows.push(currentRow.sort((a, b) => a.rect.left - b.rect.left));

      // 3. Identify column boundaries
      const xTolerance = 10;
      const columnBoundaries = [];
      candidates.forEach(c => {
          const left = c.rect.left;
          if (!columnBoundaries.some(b => Math.abs(b - left) < xTolerance)) {
              columnBoundaries.push(left);
          }
      });
      columnBoundaries.sort((a, b) => a - b);

      // Filter out rows that don't look like table rows (e.g. single element)
      const potentialTableRows = rows.filter(r => r.length > 1);
      if (potentialTableRows.length < 2) return [];

      // 4. Build table structure from rows and columns
      const tableData = [];
      potentialTableRows.forEach(row => {
          const rowData = [];
          let lastColumnIndex = -1;
          row.forEach(cell => {
              // Find which column this cell belongs to
              let colIndex = columnBoundaries.findIndex(b => Math.abs(b - cell.rect.left) < xTolerance);

              // Fill empty cells if there's a gap
              for (let i = lastColumnIndex + 1; i < colIndex; i++) {
                  rowData.push('');
              }

              rowData.push(cell.text);
              lastColumnIndex = colIndex;
              processedElements.add(cell.el);
          });
          tableData.push(rowData);
      });

      // Basic check to see if it's a valid table
      if (tableData.length > 1 && tableData[0].length > 1) {
          // Normalize column counts
          const maxCols = Math.max(...tableData.map(r => r.length));
          tableData.forEach(r => {
              while (r.length < maxCols) {
                  r.push('');
              }
          });
          allTables.push(tableData);
      }

      return allTables;
  }


  // --- Main Execution ---
  let markdownOutput = '';

  // Layer 1: Semantic <table>
  document.querySelectorAll('table').forEach(tableEl => {
    if (processedElements.has(tableEl)) return;
    const tableData = parseHtmlTable(tableEl);
    markdownOutput += toMarkdownTable(tableData);
  });

  // Layer 2: ARIA Tables
  document.querySelectorAll('[role="table"]').forEach(ariaTableEl => {
    if (processedElements.has(ariaTableEl)) return;
    const tableData = parseAriaTable(ariaTableEl);
    markdownOutput += toMarkdownTable(tableData);
  });

  // Layer 3: Visual Tables
  const visualTables = parseVisualTables();
  visualTables.forEach(tableData => {
      markdownOutput += toMarkdownTable(tableData);
  });


  // Finally, get all other text content, excluding text from processed table cells
  let otherText = '';
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
  let node;
  while (node = walker.nextNode()) {
      if (node.parentElement && !processedElements.has(node.parentElement) && node.textContent.trim()) {
          // Check if any ancestor is a processed element
          let parent = node.parentElement;
          let isProcessed = false;
          while(parent && parent !== document.body) {
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


  return markdownOutput + '\n' + otherText;
}
