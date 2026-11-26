package com.jmr.jdms.document.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClientFileInfoTest {

    @Test
    void fileNamesLoseTheirPathAndControlCharacters() {
        assertThat(ClientFileInfo.fileName("C:\\Users\\me\\scan 1.pdf")).isEqualTo("scan 1.pdf");
        assertThat(ClientFileInfo.fileName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(ClientFileInfo.fileName("bad\u0000name\r\n.pdf")).isEqualTo("badname.pdf");
        assertThat(ClientFileInfo.fileName("..")).isNull();
        assertThat(ClientFileInfo.fileName("   ")).isNull();
        assertThat(ClientFileInfo.fileName("ع".repeat(300))).hasSize(255);
    }

    @Test
    void mimeTypesAreNormalisedOrDefaulted() {
        assertThat(ClientFileInfo.mimeType("Application/PDF; charset=x")).isEqualTo("application/pdf");
        assertThat(ClientFileInfo.mimeType("text/html<script>")).isEqualTo("application/octet-stream");
        assertThat(ClientFileInfo.mimeType(null)).isEqualTo("application/octet-stream");
    }
}
