// Simple script to test if the page loads and capture console
const puppeteer = require('puppeteer');

(async () => {
  try {
    console.log('Launching browser...');
    const browser = await puppeteer.launch({ headless: true });
    const page = await browser.newPage();
    
    // Capture console messages
    const logs = [];
    page.on('console', msg => {
      const type = msg.type();
      const text = msg.text();
      logs.push({ type, text });
      console.log(`[${type.toUpperCase()}] ${text}`);
    });
    
    // Capture errors
    page.on('pageerror', error => {
      console.log(`[PAGE ERROR] ${error.message}`);
      logs.push({ type: 'error', text: error.message });
    });
    
    console.log('Navigating to http://localhost:3000...');
    await page.goto('http://localhost:3000', { waitUntil: 'networkidle0', timeout: 10000 });
    
    // Wait a bit for React to render
    await page.waitForTimeout(2000);
    
    // Get current URL
    const url = page.url();
    console.log(`Current URL: ${url}`);
    
    // Get page title
    const title = await page.title();
    console.log(`Page title: ${title}`);
    
    // Check if there's content
    const bodyText = await page.evaluate(() => document.body.innerText);
    console.log(`Body text length: ${bodyText.length}`);
    console.log(`Body text preview: ${bodyText.substring(0, 200)}`);
    
    // Take screenshot
    await page.screenshot({ path: 'debug-screenshot.png', fullPage: true });
    console.log('Screenshot saved to debug-screenshot.png');
    
    // Summary
    console.log('\n=== SUMMARY ===');
    console.log(`Total console messages: ${logs.length}`);
    console.log(`Errors: ${logs.filter(l => l.type === 'error').length}`);
    console.log(`Warnings: ${logs.filter(l => l.type === 'warning').length}`);
    
    await browser.close();
  } catch (error) {
    console.error('Failed:', error.message);
    process.exit(1);
  }
})();
