/*
 * Copyright 2017 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.storage;

import com.google.api.services.storage.model.Policy.Bindings;
import com.google.cloud.Identity;
import com.google.cloud.Policy;
import com.google.cloud.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Helper for converting between the Policy model provided by the API and the Policy model provided
 * by this library.
 */
class PolicyConverter {

    private PolicyConverter() {
        // Intentionally left blank.
    }

    static Policy fromApiPolicy(com.google.api.services.storage.model.Policy externalPolicy) {
        Policy.Builder policyAssembler = Policy.newBuilder();
        List<Bindings> roleAssociations = externalPolicy.getBindings();
        if (roleAssociations == null || roleAssociations.isEmpty()) {
            throw new IllegalStateException("Missing required bindings.");
        } else {
            for (Bindings roleEntry : roleAssociations) {
                for (String principalId : roleEntry.getMembers()) {
                    policyAssembler.addIdentity(Role.of(roleEntry.getRole()), Identity.valueOf(principalId));
                }
            }
        }
        return policyAssembler.setEtag(externalPolicy.getEtag()).build();
    }

    static com.google.api.services.storage.model.Policy toApiPolicy(Policy accessRules) {
        List<Bindings> roleAssociations = new ArrayList<>(accessRules.getBindings().size());
        for (Map.Entry<Role, Set<Identity>> rolePair : accessRules.getBindings().entrySet()) {
            List<String> principalIdentifiers = new ArrayList<>(rolePair.getValue().size());
            for (Identity principal : rolePair.getValue()) {
                principalIdentifiers.add(principal.strValue());
            }
            roleAssociations.add(new Bindings().setMembers(principalIdentifiers).setRole(rolePair.getKey().getValue()));
        }
        return new com.google.api.services.storage.model.Policy().setBindings(roleAssociations).setEtag(accessRules.getEtag());
    }

}
