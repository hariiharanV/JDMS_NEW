package com.jmr.jdms.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.jmr.jdms.shared.context.BranchScope;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClassificationTest {

    @Test
    void clearanceCoversItsLevelAndBelowOnly() {
        assertThat(Classification.CONFIDENTIAL.covers(Classification.INTERNAL)).isTrue();
        assertThat(Classification.CONFIDENTIAL.covers(Classification.CONFIDENTIAL)).isTrue();
        assertThat(Classification.CONFIDENTIAL.covers(Classification.RESTRICTED)).isFalse();
        assertThat(Classification.PUBLIC.covers(Classification.INTERNAL)).isFalse();
    }

    @Test
    void branchScopeCoversListedBranchesOrAll() {
        UUID own = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertThat(BranchScope.of(Set.of(own)).covers(own)).isTrue();
        assertThat(BranchScope.of(Set.of(own)).covers(other)).isFalse();
        assertThat(BranchScope.ALL.covers(other)).isTrue();
        assertThat(BranchScope.NONE.covers(own)).isFalse();
    }
}
