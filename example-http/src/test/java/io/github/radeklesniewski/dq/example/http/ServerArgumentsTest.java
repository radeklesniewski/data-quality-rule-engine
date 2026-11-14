package io.github.radeklesniewski.dq.example.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ServerArgumentsTest {

    @Test
    void defaultsWithoutArguments() {
        ServerArguments arguments = ServerArguments.parse();

        assertThat(arguments.port()).isEqualTo(ServerArguments.DEFAULT_PORT);
        assertThat(arguments.catalog()).isEmpty();
        assertThat(arguments.maxBodyBytes()).isEqualTo(ExampleHttpServer.DEFAULT_MAX_BODY_BYTES);
    }

    @Test
    void readsEveryArgument() {
        ServerArguments arguments = ServerArguments.parse("--port", "9090", "--catalog", "rules.json",
                "--max-body-mb", "2");

        assertThat(arguments.port()).isEqualTo(9090);
        assertThat(arguments.catalog()).contains(Path.of("rules.json"));
        assertThat(arguments.maxBodyBytes()).isEqualTo(2L * 1024 * 1024);
    }

    @Test
    void namesTheArgumentThatIsWrong() {
        assertThatThrownBy(() -> ServerArguments.parse("--port")).hasMessage("Missing value for --port");
        assertThatThrownBy(() -> ServerArguments.parse("--prot", "1"))
                .hasMessageStartingWith("Unknown argument '--prot'");
        assertThatThrownBy(() -> ServerArguments.parse("--port", "http"))
                .hasMessageContaining("--port must be a number");
        assertThatThrownBy(() -> ServerArguments.parse("--port", "70000")).hasMessageContaining("between 0 and 65535");
        assertThatThrownBy(() -> ServerArguments.parse("--max-body-mb", "0")).hasMessageContaining("between 1 and");
        assertThatThrownBy(() -> ServerArguments.parse("--max-body-mb", String.valueOf(Long.MAX_VALUE)))
                .as("would overflow when converted to bytes")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
