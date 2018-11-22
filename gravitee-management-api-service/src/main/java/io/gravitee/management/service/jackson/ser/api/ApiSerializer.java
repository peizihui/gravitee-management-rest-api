/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.management.service.jackson.ser.api;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.gravitee.definition.model.Path;
import io.gravitee.definition.model.plugins.resources.Resource;
import io.gravitee.management.model.*;
import io.gravitee.management.model.api.ApiEntity;
import io.gravitee.management.service.GroupService;
import io.gravitee.management.service.MembershipService;
import io.gravitee.management.service.PageService;
import io.gravitee.management.service.PlanService;
import io.gravitee.repository.management.model.MembershipReferenceType;
import io.gravitee.repository.management.model.RoleScope;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Titouan COMPIEGNE (titouan.compiegne at graviteesource.com)
 * @author GraviteeSource Team
 */
public abstract class ApiSerializer extends StdSerializer<ApiEntity> {

    public static String METADATA_EXPORT_VERSION = "exportVersion";
    public static String METADATA_FILTERED_FIELDS_LIST = "filteredFieldsList";
    protected ApplicationContext applicationContext;

    protected ApiSerializer(Class<ApiEntity> t) {
        super(t);
    }

    public abstract Version version();

    public boolean canHandle(ApiEntity apiEntity) {
        return version().getVersion().equals(apiEntity.getMetadata().get(METADATA_EXPORT_VERSION));
    }

    @Override
    public void serialize(ApiEntity apiEntity, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
        jsonGenerator.writeStartObject();
        if (apiEntity.getName() != null) {
            jsonGenerator.writeObjectField("name", apiEntity.getName());
        }
        if (apiEntity.getVersion() != null) {
            jsonGenerator.writeObjectField("version", apiEntity.getVersion());
        }
        if (apiEntity.getDescription() != null) {
            jsonGenerator.writeObjectField("description", apiEntity.getDescription());
        }
        if (apiEntity.getVisibility() != null) {
            jsonGenerator.writeObjectField("visibility", apiEntity.getVisibility());
        }
        if (apiEntity.getTags() != null && !apiEntity.getTags().isEmpty()) {
            jsonGenerator.writeArrayFieldStart("tags");
            for(String tag : apiEntity.getTags()) {
                jsonGenerator.writeObject(tag);
            }
            jsonGenerator.writeEndArray();
        }
        if (apiEntity.getPicture() != null) {
            jsonGenerator.writeObjectField("picture", apiEntity.getPicture());
        }
        if (apiEntity.getPaths() != null) {
            jsonGenerator.writeObjectFieldStart("paths");
            for(Map.Entry<String, Path> entry : apiEntity.getPaths().entrySet()) {
                jsonGenerator.writeObjectField(entry.getKey(), entry.getValue());
            }
            jsonGenerator.writeEndObject();
        }

        if (apiEntity.getServices() != null && !apiEntity.getServices().isEmpty()) {
            jsonGenerator.writeObjectField("services", apiEntity.getServices());
        }

        if (apiEntity.getResources() != null) {
            jsonGenerator.writeArrayFieldStart("resources");
            for(Resource resource : apiEntity.getResources()) {
                jsonGenerator.writeObject(resource);
            }
            jsonGenerator.writeEndArray();
        }

        if (apiEntity.getProperties() != null && apiEntity.getProperties().getValues() != null) {
            jsonGenerator.writeObjectField("properties", apiEntity.getProperties());
        }

        if (apiEntity.getViews() != null && !apiEntity.getViews().isEmpty()) {
            jsonGenerator.writeArrayFieldStart("views");
            for(String view : apiEntity.getViews()) {
                jsonGenerator.writeObject(view);
            }
            jsonGenerator.writeEndArray();
        }

        if (apiEntity.getLabels() != null && !apiEntity.getLabels().isEmpty()) {
            jsonGenerator.writeArrayFieldStart("labels");
            for(String label : apiEntity.getLabels()) {
                jsonGenerator.writeObject(label);
            }
            jsonGenerator.writeEndArray();
        }

        // handle filtered fields list
        List<String> filteredFieldsList = (List<String>) apiEntity.getMetadata().get(METADATA_FILTERED_FIELDS_LIST);

        if (!filteredFieldsList.contains("groups")) {
            if (apiEntity.getGroups() != null && !apiEntity.getGroups().isEmpty()) {
                Set<GroupEntity> groupEntities = applicationContext.getBean(GroupService.class).findByIds(apiEntity.getGroups());
                jsonGenerator.writeObjectField("groups", groupEntities.stream().map(GroupEntity::getName).collect(Collectors.toSet()));
            }
        }

        // members
        if (!filteredFieldsList.contains("members")) {
            Set<MemberEntity> members = applicationContext.getBean(MembershipService.class).getMembers(MembershipReferenceType.API, apiEntity.getId(), RoleScope.API);
            if (members != null) {
                members.forEach(m -> {
                    m.setId(null);
                    m.setDisplayName(null);
                    m.setEmail(null);
                    m.setPermissions(null);
                    m.setCreatedAt(null);
                    m.setUpdatedAt(null);
                });
            }
            jsonGenerator.writeObjectField("members", members == null ? Collections.emptyList() : members);
        }

        if (!filteredFieldsList.contains("pages")) {
            List<PageListItem> pageListItems = applicationContext.getBean(PageService.class).findApiPagesByApi(apiEntity.getId());
            List<PageEntity> pages = null;
            if (pageListItems != null) {
                pages = new ArrayList<>(pageListItems.size());
                List<PageEntity> finalPages = pages;
                pageListItems.forEach(f -> {
                    PageEntity pageEntity = applicationContext.getBean(PageService.class).findById(f.getId());
                    pageEntity.setId(null);
                    finalPages.add(pageEntity);
                });
            }
            jsonGenerator.writeObjectField("pages", pages == null ? Collections.emptyList() : pages);
        }

        if (!filteredFieldsList.contains("plans")) {
            Set<PlanEntity> plans = applicationContext.getBean(PlanService.class).findByApi(apiEntity.getId());
            Set<PlanEntity> plansToAdd = plans == null
                    ? Collections.emptySet()
                    : plans.stream()
                    .filter(p -> !PlanStatus.CLOSED.equals(p.getStatus()))
                    .collect(Collectors.toSet());
            jsonGenerator.writeObjectField("plans", plansToAdd);
        }
    }

    public enum Version {
        DEFAULT("default"), V_1_15("1.15");
        private final String version;

        Version(String version) {
            this.version = version;
        }

        public String getVersion() {
            return version;
        }
    }

    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }
}
