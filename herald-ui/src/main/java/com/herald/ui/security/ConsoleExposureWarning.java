package com.herald.ui.security;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
class ConsoleExposureWarning {
    ConsoleExposureWarning(ConsoleAuth auth, @Value("${server.address:127.0.0.1}") String address,
            @Value("${herald.ui.auth.secure-cookie:true}") boolean secureCookie) {
        String warning = ConsoleConfigValidator.warning(address, auth.enabled(), secureCookie);
        if (warning != null) LoggerFactory.getLogger(ConsoleExposureWarning.class).warn(warning);
    }
}
