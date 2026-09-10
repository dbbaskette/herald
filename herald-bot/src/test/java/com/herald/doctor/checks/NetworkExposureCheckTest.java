package com.herald.doctor.checks;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.herald.doctor.HealthCheck;
import static org.assertj.core.api.Assertions.assertThat;
class NetworkExposureCheckTest {
    @Test void defaultsAndOverridesAreDeliberate() {
        assertThat(new NetworkExposureCheck(Map.of()).run().status()).isEqualTo(HealthCheck.Status.OK);
        assertThat(new NetworkExposureCheck(Map.of("HERALD_UI_BIND_ADDRESS","0.0.0.0")).run().status()).isEqualTo(HealthCheck.Status.WARN);
        assertThat(new NetworkExposureCheck(Map.of("HERALD_BOT_BIND_ADDRESS","::")).run().message()).contains("internal APIs");
        assertThat(new NetworkExposureCheck(Map.of("HERALD_UI_BIND_ADDRESS","0.0.0.0","HERALD_UI_AUTH_BEARER_TOKEN","secret-value")).run().message()).doesNotContain("secret-value");
        assertThat(new NetworkExposureCheck(Map.of("SERVER_ADDRESS","0.0.0.0","HERALD_UI_BIND_ADDRESS","127.0.0.1")).run().status()).isEqualTo(HealthCheck.Status.WARN);
    }
}
