package dev.privacyagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InternalTokenFilterTest {

    private static final String TOKEN = "s3cret-token-value";

    private static InternalTokenFilter filter(String token, boolean required) {
        return new InternalTokenFilter(new SecurityProperties(token, required));
    }

    private static MockHttpServletResponse call(InternalTokenFilter filter, MockHttpServletRequest request)
            throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest api(String path) {
        var request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        return request;
    }

    @Test
    void rejectsApiCallsWithoutTheToken() throws Exception {
        var response = call(filter(TOKEN, true), api("/api/documents/1"));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
    }

    @Test
    void rejectsAWrongToken() throws Exception {
        var request = api("/api/documents/1");
        request.addHeader(InternalTokenFilter.TOKEN_HEADER, TOKEN + "x");
        assertThat(call(filter(TOKEN, true), request).getStatus()).isEqualTo(401);
    }

    @Test
    void acceptsTheTokenAndExposesTheUserToAudit() throws Exception {
        var request = api("/api/documents/1");
        request.addHeader(InternalTokenFilter.TOKEN_HEADER, TOKEN);
        request.addHeader(InternalTokenFilter.USER_HEADER, "alice");
        var seenUser = new AtomicReference<String>();
        var chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenUser.set(MDC.get(InternalTokenFilter.MDC_USER));
            }
        };
        var response = new MockHttpServletResponse();
        filter(TOKEN, true).doFilter(request, response, chain);
        MDC.clear();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seenUser.get()).isEqualTo("alice");
    }

    @Test
    void ignoresAMalformedUserHeader() throws Exception {
        var request = api("/api/documents/1");
        request.addHeader(InternalTokenFilter.TOKEN_HEADER, TOKEN);
        request.addHeader(InternalTokenFilter.USER_HEADER, "Robert'); DROP TABLE--");
        var seenUser = new AtomicReference<String>("unset");
        var chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenUser.set(MDC.get(InternalTokenFilter.MDC_USER));
            }
        };
        filter(TOKEN, true).doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(seenUser.get()).isNull();
    }

    @Test
    void leavesHealthProbesOpen() throws Exception {
        assertThat(call(filter(TOKEN, true), api("/actuator/health/liveness")).getStatus()).isEqualTo(200);
    }

    @Test
    void allowsEverythingWhenNoTokenIsConfiguredLocally() throws Exception {
        assertThat(call(filter("", false), api("/api/documents/1")).getStatus()).isEqualTo(200);
    }

    @Test
    void refusesToStartWhenATokenIsRequiredButMissing() {
        assertThatThrownBy(() -> filter(" ", true)).isInstanceOf(IllegalStateException.class);
    }
}
