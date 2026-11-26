package com.jmr.jdms.metadata.domain;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Active LOV value codes by LOV code, for validation. */
@Component
class LovCatalog {

    private final LovRepository lovs;
    private final LovValueRepository values;

    LovCatalog(LovRepository lovs, LovValueRepository values) {
        this.lovs = lovs;
        this.values = values;
    }

    /** Only existing LOVs appear in the result; a LOV without active values maps to an empty set. */
    Map<String, Set<String>> activeValues(Collection<String> lovCodes) {
        if (lovCodes.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> codesById = lovs.findByCodeIn(new HashSet<>(lovCodes)).stream()
                .collect(Collectors.toMap(Lov::getId, Lov::getCode));
        Map<String, Set<String>> result = new HashMap<>();
        codesById.values().forEach(code -> result.put(code, new HashSet<>()));
        values.findByLovIdIn(codesById.keySet()).stream().filter(LovValue::isActive)
                .forEach(value -> result.get(codesById.get(value.getLovId())).add(value.getCode()));
        return result;
    }
}
