/*
 * Copyright 2021, OpenRemote Inc.
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
package org.openremote.manager.mqtt;

import io.moquette.broker.subscriptions.Token;
import io.moquette.broker.subscriptions.Topic;
import io.moquette.interception.messages.*;
import io.netty.handler.codec.mqtt.MqttQoS;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.security.AuthContext;
import org.openremote.container.web.ConnectionConstants;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.event.ClientEventService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.security.ManagerKeycloakIdentityProvider;
import org.openremote.model.Constants;
import org.openremote.model.Container;
import org.openremote.model.asset.AssetEvent;
import org.openremote.model.asset.AssetFilter;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.event.shared.CancelEventSubscription;
import org.openremote.model.event.shared.EventSubscription;
import org.openremote.model.event.shared.SharedEvent;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.ValueUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.openremote.model.Constants.ASSET_ID_REGEXP;
import static org.openremote.model.syslog.SyslogCategory.API;

/**
 * This handler uses the {@link ClientEventService} to publish and subscribe to asset and attribute events; converting
 * subscription topics into {@link AssetFilter}s to ensure only the correct events are returned for the subscription.
 */
public class DefaultMQTTHandler extends MQTTHandler {

    public static final int PRIORITY = 1000;
    public static final String ASSET_TOPIC = "asset";
    public static final String ATTRIBUTE_TOPIC = "attribute";
    public static final String ATTRIBUTE_WRITE_TOPIC = "writeattribute";
    public static final String ATTRIBUTE_VALUE_TOPIC = "attributevalue";
    public static final String ATTRIBUTE_VALUE_WRITE_TOPIC = "writeattributevalue";
    private static final Logger LOG = SyslogCategory.getLogger(API, DefaultMQTTHandler.class);
    protected AssetStorageService assetStorageService;
    protected ClientEventService clientEventService;
    protected MqttBrokerService mqttBrokerService;
    protected MessageBrokerService messageBrokerService;
    protected ManagerKeycloakIdentityProvider identityProvider;
    protected boolean isKeycloak;

    public static boolean isAttributeTopic(Topic topic) {
        return ATTRIBUTE_TOPIC.equalsIgnoreCase(topicTokenIndexToString(topic, 2)) || ATTRIBUTE_VALUE_TOPIC.equalsIgnoreCase(topicTokenIndexToString(topic, 2));
    }

    public static boolean isAttributeWriteTopic(Topic topic) {
        return ATTRIBUTE_WRITE_TOPIC.equalsIgnoreCase(topicTokenIndexToString(topic, 2));
    }

    public static boolean isAttributeValueWriteTopic(Topic topic) {
        return ATTRIBUTE_VALUE_WRITE_TOPIC.equalsIgnoreCase(topicTokenIndexToString(topic, 2));
    }

    public static boolean isAssetTopic(Topic topic) {
        return ASSET_TOPIC.equalsIgnoreCase(topicTokenIndexToString(topic, 2));
    }

    @Override
    public int getPriority() {
        // This handler is intended to be the final handler but this can obviously be overridden by another handler
        return PRIORITY;
    }

    @Override
    public void start(Container container) throws Exception {
        super.start(container);
        ManagerIdentityService identityService = container.getService(ManagerIdentityService.class);
        assetStorageService = container.getService(AssetStorageService.class);
        clientEventService = container.getService(ClientEventService.class);
        messageBrokerService = container.getService(MessageBrokerService.class);
        mqttBrokerService = container.getService(MqttBrokerService.class);

        if (!identityService.isKeycloakEnabled()) {
            LOG.warning("MQTT connections are not supported when not using Keycloak identity provider");
            isKeycloak = false;
        } else {
            isKeycloak = true;
            identityProvider = (ManagerKeycloakIdentityProvider) identityService.getIdentityProvider();
        }
    }

    @Override
    public void onConnect(MqttConnection connection, InterceptConnectMessage msg) {
        super.onConnect(connection, msg);

        Map<String, Object> headers = prepareHeaders(connection);
        headers.put(ConnectionConstants.SESSION_OPEN, true);
        // Put a close connection runnable into the headers for the client event service
        Runnable closeRunnable = () -> mqttBrokerService.forceDisconnect(connection.getClientId());
        headers.put(ConnectionConstants.SESSION, closeRunnable);
        messageBrokerService.getProducerTemplate().sendBodyAndHeaders(ClientEventService.CLIENT_EVENT_QUEUE, null, headers);
        LOG.fine("Connected: " + connection);
    }

    @Override
    public void onDisconnect(MqttConnection connection, InterceptDisconnectMessage msg) {
        super.onDisconnect(connection, msg);

        Map<String, Object> headers = prepareHeaders(connection);
        headers.put(ConnectionConstants.SESSION_CLOSE, true);
        messageBrokerService.getProducerTemplate().sendBodyAndHeaders(ClientEventService.CLIENT_EVENT_QUEUE, null, headers);
        LOG.fine("Connection closed: " + connection);
    }

    @Override
    public void onConnectionLost(MqttConnection connection, InterceptConnectionLostMessage msg) {
        super.onConnectionLost(connection, msg);
        Map<String, Object> headers = prepareHeaders(connection);
        headers.put(ConnectionConstants.SESSION_CLOSE_ERROR, true);
        messageBrokerService.getProducerTemplate().sendBodyAndHeaders(ClientEventService.CLIENT_EVENT_QUEUE, null, headers);
        LOG.fine("Connection lost: " + connection);
    }

    @Override
    public boolean topicMatches(Topic topic) {
        return isAttributeTopic(topic) || isAssetTopic(topic) || isAttributeWriteTopic(topic) || isAttributeValueWriteTopic(topic);
    }

    @Override
    protected Logger getLogger() {
        return LOG;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public boolean canSubscribe(MqttConnection connection, Topic topic) {

        if (!isKeycloak) {
            LOG.fine("Identity provider is not keycloak");
            return false;
        }

        AuthContext authContext = connection.getAuthContext();

        if (authContext == null) {
            LOG.fine("Anonymous connection not supported: topic=" + topic + ", connection=" + connection);
            return false;
        }

        boolean isAttributeTopic = isAttributeTopic(topic);
        boolean isAssetTopic = isAssetTopic(topic);

        if (!isAssetTopic && !isAttributeTopic) {
            LOG.fine("Topic must have 3 or more tokens and third token must be 'asset, attribute or attributevalue': topic=" + topic  + ", connection=" + connection);
            return false;
        }

        if (isAssetTopic) {
            if (topic.getTokens().size() < 4 || topic.getTokens().size() > 5) {
                LOG.fine("Asset subscribe token count should be 4 or 5: topic=" + topic + ", connection=" + connection);
                return false;
            }
            if (topic.getTokens().size() == 4) {
                if (!Pattern.matches(ASSET_ID_REGEXP, topicTokenIndexToString(topic, 3))
                    && !TOKEN_MULTI_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 3))
                    && !TOKEN_SINGLE_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 3))) {
                    LOG.fine("Asset subscribe forth token must be an asset ID or wildcard: topic=" + topic + ", connection=" + connection);
                    return false;
                }
            } else if (topic.getTokens().size() == 5) {
                if (!Pattern.matches(ASSET_ID_REGEXP, topicTokenIndexToString(topic, 3))) {
                    LOG.fine("Asset subscribe forth token must be an asset ID: topic=" + topic + ", connection=" + connection);
                    return false;
                }
                if (!TOKEN_MULTI_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 4))
                    && !TOKEN_SINGLE_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 4))) {
                    LOG.fine("Asset subscribe fifth token must be a wildcard: topic=" + topic + ", connection=" + connection);
                    return false;
                }
            }
        } else {
            // Attribute topic
            if (topic.getTokens().size() < 5 || topic.getTokens().size() > 6) {
                LOG.fine("Attribute subscribe token count should be 5 or 6: topic=" + topic + ", connection=" + connection);
                return false;
            }
            if (topic.getTokens().size() == 5) {
                if (TOKEN_MULTI_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 3))) {
                    LOG.fine("Attribute subscribe multi level wildcard must be last token: topic=" + topic + ", connection=" + connection);
                    return false;
                }
                if (!Pattern.matches(ASSET_ID_REGEXP, topicTokenIndexToString(topic, 4))
                    && !TOKEN_MULTI_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 4))
                    && !TOKEN_SINGLE_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 4))) {
                    LOG.fine("Attribute subscribe fifth token must be an asset ID or a wildcard: topic=" + topic + ", connection=" + connection);
                    return false;
                }
            } else if (topic.getTokens().size() == 6) {
                if (!Pattern.matches(ASSET_ID_REGEXP, topicTokenIndexToString(topic, 4))) {
                    LOG.fine("Attribute subscribe fifth token must be an asset ID: topic=" + topic + ", connection=" + connection);
                    return false;
                }
                if (!TOKEN_MULTI_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 5))
                    && !TOKEN_SINGLE_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 5))) {
                    LOG.fine("Attribute subscribe sixth token must be a wildcard: topic=" + topic + ", connection=" + connection);
                    return false;
                }
            }
        }

        // Build filter for the topic and verify that the filter is OK for given auth context
        AssetFilter<?> filter = buildAssetFilter(connection, topic);

        if (filter == null) {
            LOG.info("Failed to process subscription topic: topic=" + topic + ", connection=" + connection);
            return false;
        }

        EventSubscription<?> subscription = new EventSubscription(
            isAssetTopic ? AssetEvent.class : AttributeEvent.class,
            filter
        );

        if (!clientEventService.authorizeEventSubscription(authContext, subscription)) {
            LOG.info("Subscription was not authorised for this user and topic: topic=" + topic + ", connection=" + connection);
            return false;
        }

        return true;

//            Asset<?> asset;
//            if(identityProvider.isRestrictedUser(authContext.getUserId())) {
//                Optional<UserAssetLink> userAsset = assetStorageService.findUserAssets(connection.realm, authContext.getUserId(), assetId).stream().findFirst();
//                asset = userAsset.map(value -> assetStorageService.find(value.getId().getAssetId())).orElse(null);
//            } else {
//                asset = assetStorageService.find(assetId, false);
//            }
//
//            if (asset == null) {
//                LOG.fine("Asset not found for topic '" + topic + "': " + connection);
//                return false;
//            }
//
//            if (isAttributeTopic && topicTokenCountGreaterThan(topic, 4)
//                && !(Token.topic.getTokens().get(4)SINGLE_LEVEL_WILDCARD.equals(topicTokens.get(4)) || MULTI_LEVEL_WILDCARD.equals(topicTokens.get(4)))) {
//                String attributeName = topicTokens.get(4);
//
//                if (!asset.hasAttribute(attributeName)) {
//                    LOG.fine("Asset attribute not found for topic '" + topic + "': " + connection);
//                    return false;
//                }
//            }
    }

    @Override
    public boolean canPublish(MqttConnection connection, Topic topic) {

        AuthContext authContext = connection.getAuthContext();

        if (!isKeycloak) {
            LOG.fine("Identity provider is not keycloak");
            return false;
        }

        if (authContext == null) {
            LOG.fine("Anonymous publish not supported: topic=" + topic + ", connection=" + connection);
            return false;
        }

        if (isAttributeWriteTopic(topic)) {
            if (topic.getTokens().size() != 3) {
                LOG.fine("Publish attribute events topic should be {realm}/{clientId}/writeattribute: topic=" + topic + ", connection=" + connection);
                return false;
            }
        } else if (isAttributeValueWriteTopic(topic)) {
            if (topic.getTokens().size() != 5 || !Pattern.matches(ASSET_ID_REGEXP, topicTokenIndexToString(topic, 4))) {
                LOG.fine("Publish attribute value topic should be {realm}/{clientId}/writeattributevalue/{attributeName}/{assetId}: topic=" + topic + ", connection=" + connection);
                return false;
            }
        }

        return true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public void doSubscribe(MqttConnection connection, Topic topic, InterceptSubscribeMessage msg) {
        boolean isAssetTopic = isAssetTopic(topic);
        String subscriptionId = msg.getTopicFilter(); // Use topic as subscription ID

        AssetFilter filter = buildAssetFilter(connection, topic);
        Class subscriptionClass = isAssetTopic ? AssetEvent.class : AttributeEvent.class;

        if (filter == null) {
            LOG.fine("Invalid event filter generated for topic '" + topic + "': " + connection);
            return;
        }

        Consumer<SharedEvent> eventConsumer = getSubscriptionEventConsumer(connection, topic, msg.getRequestedQos());

        EventSubscription subscription = new EventSubscription(
            subscriptionClass,
            filter,
            subscriptionId,
            eventConsumer
        );

        Map<String, Object> headers = prepareHeaders(connection);
        messageBrokerService.getProducerTemplate().sendBodyAndHeaders(ClientEventService.CLIENT_EVENT_QUEUE, subscription, headers);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public void doUnsubscribe(MqttConnection connection, Topic topic, InterceptUnsubscribeMessage msg) {
        String subscriptionId = topic.toString();
        boolean isAssetTopic = subscriptionId.startsWith(ASSET_TOPIC);
        Map<String, Object> headers = prepareHeaders(connection);
        Class<SharedEvent> subscriptionClass = (Class) (isAssetTopic ? AssetEvent.class : AttributeEvent.class);
        CancelEventSubscription cancelEventSubscription = new CancelEventSubscription(subscriptionClass, subscriptionId);
        messageBrokerService.getProducerTemplate().sendBodyAndHeaders(ClientEventService.CLIENT_EVENT_QUEUE, cancelEventSubscription, headers);
    }

    @Override
    public void doPublish(MqttConnection connection, Topic topic, InterceptPublishMessage msg) {
        List<String> topicTokens = topic.getTokens().stream().map(Token::toString).collect(Collectors.toList());
        boolean isValueWrite = topicTokens.get(2).equals(ATTRIBUTE_VALUE_WRITE_TOPIC);
        String payloadContent = msg.getPayload().toString(StandardCharsets.UTF_8);
        AttributeEvent attributeEvent;

        if (isValueWrite) {
            String attributeName = topicTokens.get(3);
            String assetId = topicTokens.get(4);
            Object value = ValueUtil.parse(payloadContent).orElse(null);
            attributeEvent = new AttributeEvent(assetId, attributeName, value);
        } else {
            attributeEvent = ValueUtil.parse(payloadContent, AttributeEvent.class).orElse(null);
        }

        if (attributeEvent == null) {
            LOG.fine("Failed to parse payload for publish topic '" + topic + "': " + connection);
            return;
        }

        Map<String, Object> headers = prepareHeaders(connection);
        messageBrokerService.getProducerTemplate().sendBodyAndHeaders(ClientEventService.CLIENT_EVENT_QUEUE, attributeEvent, headers);
    }

    public static AssetFilter<?> buildAssetFilter(MqttConnection connection, Topic topic) {
        boolean isAttributeTopic = isAttributeTopic(topic);
        boolean isAssetTopic = isAssetTopic(topic);

        String realm = connection.getRealm();
        List<String> assetIds = new ArrayList<>();
        List<String> parentIds = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        List<String> attributeNames = new ArrayList<>();
        String firstTokenStr = topicTokenIndexToString(topic, 3);

        if (isAssetTopic) {
            if (topic.getTokens().size() == 4) {
                if (TOKEN_MULTI_LEVEL_WILDCARD.equals(firstTokenStr)) {
                    //realm/clientId/asset/#
                    // No filtering required
                } else if (TOKEN_SINGLE_LEVEL_WILDCARD.equals(firstTokenStr)) {
                    //realm/clientId/asset/+
                    parentIds.add(null);
                } else {
                    //realm/clientId/asset/{assetId}
                    assetIds.add(firstTokenStr);
                }
            } else if (topic.getTokens().size() == 5) {
                String secondTokenStr = topicTokenIndexToString(topic, 4);

                if (TOKEN_MULTI_LEVEL_WILDCARD.equals(secondTokenStr)) {
                    //realm/clientId/asset/assetId/#
                    paths.add(firstTokenStr);
                } else if (TOKEN_SINGLE_LEVEL_WILDCARD.equals(secondTokenStr)) {
                    //realm/clientId/asset/assetId/+
                    parentIds.add(firstTokenStr);
                }
            } else {
                return null;
            }
        } else {
            if (!TOKEN_SINGLE_LEVEL_WILDCARD.equals(firstTokenStr)) {
                attributeNames.add(firstTokenStr);
            }
            if (topic.getTokens().size() == 5) {
                String secondTokenStr = topicTokenIndexToString(topic, 4);
                //realm/clientId/attribute/{attributeName|+}/{assetId|+|*}
                if (TOKEN_MULTI_LEVEL_WILDCARD.equals(secondTokenStr)) {
                    //realm/clientId/attribute/+/#
                    // No filtering required
                } else if (TOKEN_SINGLE_LEVEL_WILDCARD.equals(secondTokenStr)) {
                    //realm/clientId/attribute/+/+
                    parentIds.add(null);
                } else {
                    //realm/clientId/attribute/+/{assetId}
                    assetIds.add(secondTokenStr);
                }
            } else if (topic.getTokens().size() == 6) {
                //realm/clientId/attribute/{attributeName|+}/{assetId}/{+|*}
                String thirdTokenStr = topicTokenIndexToString(topic, 5);

                if (TOKEN_MULTI_LEVEL_WILDCARD.equals(thirdTokenStr)) {
                    paths.add(topicTokenIndexToString(topic, 4));
                } else if (TOKEN_SINGLE_LEVEL_WILDCARD.equals(thirdTokenStr)) {
                    parentIds.add(topicTokenIndexToString(topic, 4));
                }
            } else {
                return null;
            }
        }

        AssetFilter<?> assetFilter = new AssetFilter<>().setRealm(realm);
        if (!assetIds.isEmpty()) {
            assetFilter.setAssetIds(assetIds.toArray(new String[0]));
        }
        if (!parentIds.isEmpty()) {
            assetFilter.setParentIds(parentIds.toArray(new String[0]));
        }
        if (!paths.isEmpty()) {
            assetFilter.setPath(paths.toArray(new String[0]));
        }
        if (!attributeNames.isEmpty()) {
            assetFilter.setAttributeNames(attributeNames.toArray(new String[0]));
        }
        return assetFilter;
    }

    protected Consumer<SharedEvent> getSubscriptionEventConsumer(MqttConnection connection, Topic topic, MqttQoS mqttQoS) {
        boolean isValueSubscription = ATTRIBUTE_VALUE_TOPIC.equalsIgnoreCase(topicTokenIndexToString(topic, 2));
        boolean isAssetTopic = isAssetTopic(topic);

        // Build topic expander (replace wildcards) so it isn't computed for each event
        Function<SharedEvent, String> topicExpander;

        if (isAssetTopic) {
            String topicStr = topic.toString();
            String replaceToken = topicStr.endsWith(TOKEN_MULTI_LEVEL_WILDCARD) ? TOKEN_MULTI_LEVEL_WILDCARD : topicStr.endsWith(TOKEN_SINGLE_LEVEL_WILDCARD) ? TOKEN_SINGLE_LEVEL_WILDCARD : null;
            topicExpander = ev -> replaceToken != null ? topicStr.replace(replaceToken, ((AssetEvent)ev).getAssetId()) : topicStr;
        } else {
            String topicStr = topic.toString();
            boolean injectAttributeName = TOKEN_SINGLE_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 3));

            if (injectAttributeName) {
                topicStr = topicStr.replaceFirst("\\"+ TOKEN_SINGLE_LEVEL_WILDCARD, "\\$");
            }

            String replaceToken = topicStr.endsWith(TOKEN_MULTI_LEVEL_WILDCARD) ? TOKEN_MULTI_LEVEL_WILDCARD : topicStr.endsWith(TOKEN_SINGLE_LEVEL_WILDCARD) ? TOKEN_SINGLE_LEVEL_WILDCARD : null;
            String finalTopicStr = topicStr;
            topicExpander = ev -> {
                String expanded = replaceToken != null ? finalTopicStr.replace(replaceToken, ((AttributeEvent)ev).getAssetId()) : finalTopicStr;
                if (injectAttributeName) {
                    expanded = expanded.replace("$", ((AttributeEvent)ev).getAttributeName());
                }
                return expanded;
            };
        }


        return ev -> {

            if (isAssetTopic) {
                if (ev instanceof AssetEvent) {
                    mqttBrokerService.publishMessage(topicExpander.apply(ev), ev, mqttQoS);
                }
            } else {
                if (ev instanceof AttributeEvent) {
                    AttributeEvent attributeEvent = (AttributeEvent) ev;

                    if (isValueSubscription) {
                        mqttBrokerService.publishMessage(topicExpander.apply(ev), attributeEvent.getValue().orElse(null), mqttQoS);
                    } else {
                        mqttBrokerService.publishMessage(topicExpander.apply(ev), ev, mqttQoS);
                    }
                }
            }
        };
    }

    public static Map<String, Object> prepareHeaders(MqttConnection connection) {
        Map<String, Object> headers = new HashMap<>();
        headers.put(ConnectionConstants.SESSION_KEY, connection.getClientId());
        headers.put(ClientEventService.HEADER_CONNECTION_TYPE, ClientEventService.HEADER_CONNECTION_TYPE_MQTT);
        headers.put(Constants.AUTH_CONTEXT, connection.getAuthContext());
        return headers;
    }
}
