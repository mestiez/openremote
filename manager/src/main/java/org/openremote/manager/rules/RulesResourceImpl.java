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
package org.openremote.manager.rules;

import org.openremote.container.timer.TimerService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.web.ManagerWebResource;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetQuery;
import org.openremote.model.asset.BaseAssetQuery;
import org.openremote.model.asset.UserAsset;
import org.openremote.model.http.RequestParams;
import org.openremote.model.rules.AssetRuleset;
import org.openremote.model.rules.GlobalRuleset;
import org.openremote.model.rules.RulesResource;
import org.openremote.model.rules.TenantRuleset;
import org.openremote.model.rules.geofence.GeofenceDefinition;
import org.openremote.model.security.Tenant;

import javax.ws.rs.BeanParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.logging.Logger;

import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;

public class RulesResourceImpl extends ManagerWebResource implements RulesResource {

    private static final Logger LOG = Logger.getLogger(RulesResourceImpl.class.getName());

    final protected RulesetStorageService rulesetStorageService;
    final protected AssetStorageService assetStorageService;
    final protected RulesService rulesService;

    public RulesResourceImpl(TimerService timerService,
                             ManagerIdentityService identityService,
                             RulesetStorageService rulesetStorageService,
                             AssetStorageService assetStorageService,
                             RulesService rulesService) {
        super(timerService, identityService);
        this.rulesetStorageService = rulesetStorageService;
        this.assetStorageService = assetStorageService;
        this.rulesService = rulesService;
    }

    /* ################################################################################################# */

    @Override
    public GlobalRuleset[] getGlobalRulesets(@BeanParam RequestParams requestParams) {
        if (!isSuperUser()) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        List<GlobalRuleset> result = rulesetStorageService.findGlobalRulesets();
        return result.toArray(new GlobalRuleset[result.size()]);
    }

    @Override
    public TenantRuleset[] getTenantRulesets(@BeanParam RequestParams requestParams, String realmId) {
        if (!isRealmAccessibleByUser(realmId) || isRestrictedUser()) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        List<TenantRuleset> result = rulesetStorageService.findTenantRulesets(realmId);
        return result.toArray(new TenantRuleset[result.size()]);
    }

    @Override
    public AssetRuleset[] getAssetRulesets(@BeanParam RequestParams requestParams, String assetId) {
        Asset asset = assetStorageService.find(assetId, false);
        if (asset == null)
            return new AssetRuleset[0];

        if (!isRealmAccessibleByUser(asset.getTenantRealm())) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        if (isRestrictedUser() && !assetStorageService.isUserAsset(getUserId(), assetId)) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        List<AssetRuleset> result = rulesetStorageService.findAssetRulesets(asset.getRealmId(), assetId);
        return result.toArray(new AssetRuleset[result.size()]);
    }

    /* ################################################################################################# */

    @Override
    public void createGlobalRuleset(@BeanParam RequestParams requestParams, GlobalRuleset ruleset) {
        if (!isSuperUser()) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        rulesetStorageService.merge(ruleset);
    }

    @Override
    public GlobalRuleset getGlobalRuleset(@BeanParam RequestParams requestParams, Long id) {
        if (!isSuperUser()) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        GlobalRuleset ruleset = rulesetStorageService.findById(GlobalRuleset.class, id);
        if (ruleset == null) {
            throw new WebApplicationException(NOT_FOUND);
        }
        return ruleset;
    }

    @Override
    public void updateGlobalRuleset(@BeanParam RequestParams requestParams, Long id, GlobalRuleset ruleset) {
        if (!isSuperUser()) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        GlobalRuleset existingRuleset = rulesetStorageService.findById(GlobalRuleset.class, id);
        if (existingRuleset == null)
            throw new WebApplicationException(NOT_FOUND);
        rulesetStorageService.merge(ruleset);
    }

    @Override
    public void deleteGlobalRuleset(@BeanParam RequestParams requestParams, Long id) {
        if (!isSuperUser()) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        rulesetStorageService.delete(GlobalRuleset.class, id);
    }

    /* ################################################################################################# */

    @Override
    public void createTenantRuleset(@BeanParam RequestParams requestParams, TenantRuleset ruleset) {
        Tenant tenant = identityService.getIdentityProvider().getTenantForRealmId(ruleset.getRealmId());
        if (tenant == null) {
            throw new WebApplicationException(BAD_REQUEST);
        }
        if (!isTenantActiveAndAccessible(tenant) || isRestrictedUser()) {
            LOG.fine("Forbidden access for user '" + getUsername() + "': " + tenant);
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        rulesetStorageService.merge(ruleset);
    }

    @Override
    public TenantRuleset getTenantRuleset(@BeanParam RequestParams requestParams, Long id) {
        TenantRuleset ruleset = rulesetStorageService.findById(TenantRuleset.class, id);
        if (ruleset == null) {
            throw new WebApplicationException(NOT_FOUND);
        }
        Tenant tenant = identityService.getIdentityProvider().getTenantForRealmId(ruleset.getRealmId());
        if (tenant == null) {
            throw new WebApplicationException(BAD_REQUEST);
        }
        if (!isTenantActiveAndAccessible(tenant) || isRestrictedUser()) {
            LOG.fine("Forbidden access for user '" + getUsername() + "': " + tenant);
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        return ruleset;
    }

    @Override
    public void updateTenantRuleset(@BeanParam RequestParams requestParams, Long id, TenantRuleset ruleset) {
        TenantRuleset existingRuleset = rulesetStorageService.findById(TenantRuleset.class, id);
        if (existingRuleset == null) {
            throw new WebApplicationException(NOT_FOUND);
        }
        Tenant tenant = identityService.getIdentityProvider().getTenantForRealmId(existingRuleset.getRealmId());
        if (tenant == null) {
            throw new WebApplicationException(BAD_REQUEST);
        }
        if (!isTenantActiveAndAccessible(tenant) || isRestrictedUser()) {
            LOG.fine("Forbidden access for user '" + getUsername() + "': " + tenant);
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        if (!id.equals(ruleset.getId())) {
            throw new WebApplicationException("Requested ID and ruleset ID don't match", BAD_REQUEST);
        }
        if (!existingRuleset.getRealmId().equals(ruleset.getRealmId())) {
            throw new WebApplicationException("Requested realm and existing ruleset realm must match", BAD_REQUEST);
        }
        rulesetStorageService.merge(ruleset);
    }

    @Override
    public void deleteTenantRuleset(@BeanParam RequestParams requestParams, Long id) {
        TenantRuleset ruleset = rulesetStorageService.findById(TenantRuleset.class, id);
        if (ruleset == null) {
            return;
        }
        Tenant tenant = identityService.getIdentityProvider().getTenantForRealmId(ruleset.getRealmId());
        if (tenant == null) {
            throw new WebApplicationException(BAD_REQUEST);
        }
        if (!isTenantActiveAndAccessible(tenant) || isRestrictedUser()) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        rulesetStorageService.delete(TenantRuleset.class, id);
    }

    /* ################################################################################################# */

    @Override
    public void createAssetRuleset(@BeanParam RequestParams requestParams, AssetRuleset ruleset) {
        String assetId = ruleset.getAssetId();
        if (assetId == null || assetId.length() == 0) {
            throw new WebApplicationException("Missing asset identifier value", BAD_REQUEST);
        }
        Asset asset = assetStorageService.find(assetId, false);
        if (asset == null) {
            throw new WebApplicationException(NOT_FOUND);
        }
        if (!isRealmAccessibleByUser(asset.getTenantRealm())) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        if (isRestrictedUser() && !assetStorageService.isUserAsset(getUserId(), asset.getId())) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        rulesetStorageService.merge(ruleset);
    }

    @Override
    public AssetRuleset getAssetRuleset(@BeanParam RequestParams requestParams, Long id) {
        AssetRuleset ruleset = rulesetStorageService.findById(AssetRuleset.class, id);
        if (ruleset == null) {
            throw new WebApplicationException(NOT_FOUND);
        }
        Asset asset = assetStorageService.find(ruleset.getAssetId(), false);
        if (asset == null) {
            throw new WebApplicationException(NOT_FOUND);
        }
        if (!isRealmAccessibleByUser(asset.getTenantRealm())) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        if (isRestrictedUser() && !assetStorageService.isUserAsset(getUserId(), asset.getId())) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        return ruleset;
    }

    @Override
    public void updateAssetRuleset(@BeanParam RequestParams requestParams, Long id, AssetRuleset ruleset) {
        AssetRuleset existingRuleset = rulesetStorageService.findById(AssetRuleset.class, id);
        if (existingRuleset == null) {
            throw new WebApplicationException(NOT_FOUND);
        }
        Asset asset = assetStorageService.find(existingRuleset.getAssetId(), false);
        if (asset == null) {
            throw new WebApplicationException(NOT_FOUND);
        }
        if (!isRealmAccessibleByUser(asset.getTenantRealm())) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        if (isRestrictedUser() && !assetStorageService.isUserAsset(getUserId(), asset.getId())) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        if (!id.equals(ruleset.getId())) {
            throw new WebApplicationException("Requested ID and ruleset ID don't match", BAD_REQUEST);
        }
        if (!existingRuleset.getAssetId().equals(ruleset.getAssetId())) {
            throw new WebApplicationException("Can't update asset ID, delete and create the ruleset to reassign",
                                              BAD_REQUEST);
        }
        rulesetStorageService.merge(ruleset);
    }

    @Override
    public void deleteAssetRuleset(@BeanParam RequestParams requestParams, Long id) {
        AssetRuleset ruleset = rulesetStorageService.findById(AssetRuleset.class, id);
        if (ruleset == null) {
            return;
        }
        Asset asset = assetStorageService.find(ruleset.getAssetId(), false);
        if (asset == null) {
            throw new WebApplicationException(NOT_FOUND);
        }
        if (!isRealmAccessibleByUser(asset.getTenantRealm())) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        if (isRestrictedUser() && !assetStorageService.isUserAsset(getUserId(), asset.getId())) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        rulesetStorageService.delete(AssetRuleset.class, id);
    }

    @Override
    public GeofenceDefinition[] getAssetGeofences(String assetId) {
        Asset asset;

        asset = assetStorageService.find(
            new AssetQuery()
                .select(new BaseAssetQuery.Select(BaseAssetQuery.Include.ONLY_ID_AND_NAME))
                .id(assetId));

        if (asset == null)
            return new GeofenceDefinition[0];

        // If asset is linked to users then only those users can get the geofences for it
        List<UserAsset> userAssetLinks = assetStorageService.findUserAssets(asset.getRealmId(), null, assetId);

        // If not linked to users check if asset is marked as public read
        // TODO: Change for public write when implemented
        if (userAssetLinks.isEmpty() && !asset.isAccessPublicRead()) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }

        if (isAuthenticated()) {
            // Check asset is linked to authenticated user or super user is logged in
            if (!isSuperUser() || userAssetLinks.stream().noneMatch(userAssetLink -> userAssetLink.getId().getUserId().equals(getUserId()))) {
                throw new WebApplicationException(Response.Status.FORBIDDEN);
            }

        }

        return rulesService.getAssetGeofences(assetId);
    }

}
