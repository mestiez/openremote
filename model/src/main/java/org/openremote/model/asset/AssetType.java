/*
 * Copyright 2017, OpenRemote Inc.
 *
 * See the CONTRIBUTORS.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openremote.model.asset;

import elemental.json.Json;
import elemental.json.JsonObject;
import org.openremote.model.Attribute;
import org.openremote.model.AttributeType;
import org.openremote.model.Attributes;
import org.openremote.model.Meta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.openremote.model.AttributeType.INTEGER;
import static org.openremote.model.AttributeType.STRING;
import static org.openremote.model.Constants.ASSET_NAMESPACE;
import static org.openremote.model.asset.AssetMeta.*;

/**
 * Asset type is an arbitrary string. It should be URI. This enum contains
 * the well-known URIs for functionality we want to depend on in our platform.
 * <p>
 * TODO https://people.eecs.berkeley.edu/~arka/papers/buildsys2015_metadatasurvey.pdf
 */
public enum AssetType {

    CUSTOM(null, true, null),

    BUILDING(ASSET_NAMESPACE + ":building", true, new Attributes().put(
        new Attribute("area", INTEGER)
            .setMeta(new Meta()
                .add(createMetaItem(LABEL, Json.create("Surface area")))
                .add(createMetaItem(DESCRIPTION, Json.create("Floor area of building measured in m²")))
                .add(createMetaItem(ABOUT, Json.create("http://project-haystack.org/tag/area")))
            ),
        new Attribute("geoStreet", STRING)
            .setMeta(new Meta()
                .add(createMetaItem(LABEL, Json.create("Street")))
                .add(createMetaItem(ABOUT, Json.create("http://project-haystack.org/tag/geoStreet")))
            ),
        new Attribute("geoPostalCode", AttributeType.INTEGER)
            .setMeta(new Meta()
                .add(createMetaItem(LABEL, Json.create("Postal Code")))
                .add(createMetaItem(ABOUT, Json.create("http://project-haystack.org/tag/geoPostalCode")))
            ),
        new Attribute("geoCity", STRING)
            .setMeta(new Meta()
                .add(createMetaItem(LABEL, Json.create("City")))
                .add(createMetaItem(ABOUT, Json.create("http://project-haystack.org/tag/geoCity")))
            ),
        new Attribute("geoCountry", STRING)
            .setMeta(new Meta()
                .add(createMetaItem(LABEL, Json.create("Country")))
                .add(createMetaItem(ABOUT, Json.create("http://project-haystack.org/tag/geoCountry")))
            )
    ).getJsonObject()),

    FLOOR(ASSET_NAMESPACE + ":floor", true, null),

    RESIDENCE(ASSET_NAMESPACE + ":residence", true, null),

    ROOM(ASSET_NAMESPACE + ":room", true, null),

    AGENT(ASSET_NAMESPACE + ":agent", true, null),

    /**
     *  When a Thing asset is modified (created, updated, deleted), its attributes are examined
     *  and linked to and unlinked from the configured Protocol.
     */
    THING(ASSET_NAMESPACE + ":thing", true, null);

    final protected String value;
    final protected JsonObject defaultAttributes;

    AssetType(String value, boolean editable, JsonObject defaultAttributes) {
        this.value = value;
        this.defaultAttributes = defaultAttributes;
    }

    public String getValue() {
        return value;
    }

    public JsonObject getDefaultAttributes() {
        return defaultAttributes;
    }

    public static AssetType[] valuesSorted() {
        List<AssetType> list = new ArrayList<>(Arrays.asList(values()));

        list.sort(Comparator.comparing(Enum::name));
        if (list.contains(CUSTOM)) {
            // CUSTOM should be first
            list.remove(CUSTOM);
            list.add(0, CUSTOM);
        }

        return list.toArray(new AssetType[list.size()]);
    }

    public static AssetType getByValue(String value) {
        if (value == null)
            return CUSTOM;
        for (AssetType assetType : values()) {
            if (value.equals(assetType.getValue()))
                return assetType;
        }
        return CUSTOM;
    }
}
