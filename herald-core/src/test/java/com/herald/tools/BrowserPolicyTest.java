package com.herald.tools;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class BrowserPolicyTest {
    @Test void rejectsWildcardCredentialsSchemesAndUnlistedOrigins() {
        assertThatThrownBy(() -> new BrowserPolicy("https://*.example.com",false)).isInstanceOf(SecurityException.class);
        var policy=new BrowserPolicy("https://example.com",false);
        for(String url:new String[]{"file:///etc/passwd","https://user:pass@example.com","https://example.com.attacker.test","http://example.com","https://example.com:444"})
            assertThatThrownBy(() -> policy.check(url)).isInstanceOf(SecurityException.class);
    }
    @Test void privateOriginsRequireThePackagePrivateFixtureOverride() {
        var policy=new BrowserPolicy("http://127.0.0.1:8765",false);
        assertThatThrownBy(() -> policy.check("http://127.0.0.1:8765/")).isInstanceOf(SecurityException.class);
        new BrowserPolicy("http://127.0.0.1:8765",true).check("http://127.0.0.1:8765/");
        assertThatThrownBy(() -> new BrowserPolicy("http://100.100.100.200",false).check("http://100.100.100.200/"))
                .isInstanceOf(SecurityException.class);
    }
}
