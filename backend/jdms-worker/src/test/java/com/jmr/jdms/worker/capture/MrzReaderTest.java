package com.jmr.jdms.worker.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** B-15 follow-up: a passport's machine-readable zone, read from OCR text (ICAO 9303 specimen). */
class MrzReaderTest {

    static final String NAMES = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<";
    static final String DATA = "L898902C36UTO7408122F1204159ZE184226B<<<<<10";

    @Test
    void readsTheSpecimenAndItsCheckDigits() {
        MrzReader.Mrz mrz = MrzReader.read(List.of("PASSPORT", NAMES, DATA)).orElseThrow();

        assertThat(mrz.surname()).isEqualTo("ERIKSSON");
        assertThat(mrz.givenNames()).isEqualTo("ANNA MARIA");
        assertThat(mrz.fullName()).isEqualTo("ANNA MARIA ERIKSSON");
        assertThat(mrz.documentNumber()).isEqualTo("L898902C3");
        assertThat(mrz.documentNumberChecked()).isTrue();
        assertThat(mrz.nationality()).isEqualTo("UTO");
        assertThat(mrz.birthDate()).isEqualTo(LocalDate.of(1974, 8, 12));
        assertThat(mrz.birthDateChecked()).isTrue();
        assertThat(mrz.sex()).isEqualTo("F");
        assertThat(mrz.expiryDate()).isEqualTo(LocalDate.of(2012, 4, 15));
        assertThat(mrz.expiryDateChecked()).isTrue();
    }

    @Test
    void toleratesTheUsualOcrNoise() {
        // spaces, filler read as K, a stray digit after the number's check digit
        MrzReader.Mrz mrz = MrzReader.read(List.of(
                "P<UTOERIKSSON<<ANNA<MARIA<<<<<< <<< KK KKK KKK",
                "L898902C361UTO7408 122F1204 159ZE184226B<<<<cc<h")).orElseThrow();

        assertThat(mrz.fullName()).isEqualTo("ANNA MARIA ERIKSSON");
        assertThat(mrz.documentNumber()).isEqualTo("L898902C3");
        assertThat(mrz.expiryDate()).isEqualTo(LocalDate.of(2012, 4, 15));
        assertThat(mrz.documentNumberChecked()).isTrue();
        assertThat(mrz.expiryDateChecked()).isTrue();
    }

    @Test
    void lettersAndDigitsThatOcrConfusesAreReadByPosition() {
        // "UT0" with a zero in the country code, "O" for zero and "S" for five in the dates
        MrzReader.Mrz mrz = MrzReader.read(List.of(NAMES, "L898902C36UT074O8122F12O4l59ZE184226B<<<<<10"))
                .orElseThrow();

        assertThat(mrz.nationality()).isEqualTo("UTO");
        assertThat(mrz.birthDate()).isEqualTo(LocalDate.of(1974, 8, 12));
        assertThat(mrz.birthDateChecked()).isTrue();
        assertThat(mrz.expiryDate()).isEqualTo(LocalDate.of(2012, 4, 15));
    }

    @Test
    void aWrongCheckDigitIsReportedNotHidden() {
        MrzReader.Mrz mrz = MrzReader.read(List.of(NAMES, "L898902C37UTO7408121F1204158ZE184226B<<<<<10"))
                .orElseThrow();

        assertThat(mrz.documentNumberChecked()).isFalse();
        assertThat(mrz.birthDateChecked()).isFalse();
        assertThat(mrz.expiryDateChecked()).isFalse();
    }

    @Test
    void textWithoutAZoneHasNone() {
        assertThat(MrzReader.read(List.of("Customer Name: Ahmed", "CIF: C123456", "12/12/2030"))).isEmpty();
    }

    @Test
    void checkDigitsFollowIcao9303() {
        assertThat(MrzReader.check("L898902C3")).isEqualTo(6);
        assertThat(MrzReader.check("740812")).isEqualTo(2);
        assertThat(MrzReader.check("120415")).isEqualTo(9);
    }
}
