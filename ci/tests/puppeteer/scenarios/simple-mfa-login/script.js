const puppeteer = require('puppeteer');
const assert = require('assert');
const cas = require('../../cas.js');

(async () => {
    const browser = await puppeteer.launch(cas.browserOptions());
    const page = await cas.newPage(browser);
    await page.goto("https://localhost:8443/cas/login?authn_method=mfa-simple");
    await cas.loginWith(page, "casuser", "Mellon");

    // await page.waitForTimeout(5000)

    await cas.assertVisibility(page, '#token')

    await browser.close();
})();
