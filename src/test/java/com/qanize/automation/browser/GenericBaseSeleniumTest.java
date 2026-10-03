package com.qanize.automation.browser;

import com.qanize.automation.config.WebsiteConfig;
import com.qanize.automation.config.WebsiteConfigLoader;

public abstract class GenericBaseSeleniumTest extends BaseSeleniumTest {

    protected WebsiteConfig websiteConfig() {
        return WebsiteConfigLoader.loadSelected();
    }
}