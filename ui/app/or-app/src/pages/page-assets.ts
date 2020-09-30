import {css, customElement, html, property, query, TemplateResult, unsafeCSS} from "lit-element";
import "@openremote/or-asset-tree";
import "@openremote/or-asset-viewer";
import {
    OrAssetViewer,
    OrAssetViewerEditToggleEvent,
    OrAssetViewerRequestEditToggleEvent,
    OrAssetViewerSaveEvent,
    ViewerConfig
} from "@openremote/or-asset-viewer";
import {
    AssetTreeConfig,
    OrAssetTree,
    OrAssetTreeAddEvent,
    OrAssetTreeAssetEvent,
    OrAssetTreeRequestSelectionEvent,
    OrAssetTreeSelectionEvent
} from "@openremote/or-asset-tree";
import {DefaultBoxShadow, Util} from "@openremote/core";
import {AppStateKeyed} from "../app";
import {Page, router} from "../types";
import {EnhancedStore} from "@reduxjs/toolkit";
import {showOkCancelDialog} from "@openremote/or-mwc-components/dist/or-mwc-dialog";
import i18next from "i18next";
import {AssetEventCause} from "@openremote/model";

export interface PageAssetsConfig {
    viewer?: ViewerConfig;
    tree?: AssetTreeConfig;
}

export function pageAssetsProvider<S extends AppStateKeyed>(store: EnhancedStore<S>, config?: PageAssetsConfig) {
    return {
        routes: [
            "assets",
            "assets/:editMode",
            "assets/:editMode/:id"
        ],
        pageCreator: () => {
            const page = new PageAssets(store);
            if (config) {
                page.config = config;
            }
            return page;
        }
    };
}

export function getAssetsRoute(editMode?: boolean, assetId?: string) {
    let route = "assets/" + (editMode ? "true" : "false");
    if (assetId) {
        route += "/" + assetId;
    }

    return route;
}

@customElement("page-assets")
class PageAssets<S extends AppStateKeyed> extends Page<S>  {

    static get styles() {
        // language=CSS
        return css`
            
            or-asset-tree {
                align-items: stretch;
                z-index: 1;
            }
            
            .hideMobile {
                display: none;
            }
                
            or-asset-viewer {
                align-items: stretch;
                z-index: 0;
            }
            
            @media only screen and (min-width: 768px){
                or-asset-tree {
                    width: 300px;
                    min-width: 300px;
                    box-shadow: ${unsafeCSS(DefaultBoxShadow)} 
                }
                
                .hideMobile {
                    display: flex;
                }
                
                or-asset-viewer,
                or-asset-viewer.hideMobile {
                    display: initial;
                }
            }
        `;
    }

    @property()
    public config?: PageAssetsConfig;

    @property()
    protected _editMode: boolean = false;

    @property()
    protected _assetIds?: string[];

    @query("#tree")
    protected _tree!: OrAssetTree;

    @query("#viewer")
    protected _viewer!: OrAssetViewer;

    protected _addedAssetId?: string;

    get name(): string {
        return "assets";
    }

    constructor(store: EnhancedStore<S>) {
        super(store);
        this.addEventListener(OrAssetTreeRequestSelectionEvent.NAME, this._onAssetSelectionRequested);
        this.addEventListener(OrAssetTreeSelectionEvent.NAME, this._onAssetSelectionChanged);
        this.addEventListener(OrAssetViewerRequestEditToggleEvent.NAME, this._onEditToggleRequested);
        this.addEventListener(OrAssetViewerEditToggleEvent.NAME, this._onEditToggle);
        this.addEventListener(OrAssetTreeAddEvent.NAME, this._onAssetAdd);
        this.addEventListener(OrAssetViewerSaveEvent.NAME, this._onAssetSave);
        this.addEventListener(OrAssetTreeAssetEvent.NAME, this._onAssetTreeAssetEvent);
    }

    protected render(): TemplateResult | void {
        return html`
            <or-asset-tree id="tree" .config="${this.config && this.config.tree ? this.config.tree : null}" class="${this._assetIds && this._assetIds.length === 1 ? "hideMobile" : ""}" .selectedIds="${this._assetIds}"></or-asset-tree>
            <or-asset-viewer id="viewer" .config="${this.config && this.config.viewer ? this.config.viewer : undefined}" class="${!this._assetIds || this._assetIds.length !== 1 ? "hideMobile" : ""}" .editMode="${this._editMode}"></or-asset-viewer>
        `;
    }

    stateChanged(state: S) {
        // State is only utilised for initial loading
        this._editMode = !!(state.app.params && state.app.params.editMode === "true");
        this._assetIds = state.app.params && state.app.params.id ? [state.app.params.id as string] : undefined;
    }

    protected _onAssetSelectionRequested(event: OrAssetTreeRequestSelectionEvent) {
        const isModified = this._viewer.isModified();

        if (!isModified) {
            return;
        }

        // Prevent the request and check if user wants to lose changes
        event.detail.allow = false;

        this._confirmContinue(() => {
            const nodes = event.detail.detail.newNodes;

            if (Util.objectsEqual(nodes, event.detail.detail.oldNodes)) {
                // User has clicked the same node so let's force reload it
                this._viewer.reloadAsset();
            } else {
                this._assetIds = nodes.map((node) => node.asset.id!);
                this._viewer.assetId = nodes.length === 1 ? nodes[0].asset!.id : undefined;
                this._updateRoute(true);
            }
        });
    }

    protected _onAssetSelectionChanged(event: OrAssetTreeSelectionEvent) {
        const nodes = event.detail.newNodes;
        const newIds = event.detail.newNodes.map((node) => node.asset.id!);
        if (Util.objectsEqual(newIds, this._assetIds)) {
            return;
        }

        this._assetIds = newIds;
        this._viewer.assetId = nodes.length === 1 ? nodes[0].asset!.id : undefined;
        this._updateRoute(true);
    }

    protected _onEditToggleRequested(event: OrAssetViewerRequestEditToggleEvent) {
        // Block the request if current asset is modified then show dialog then navigate
        const isModified = this._viewer.isModified();

        if (!isModified) {
            return;
        }

        event.detail.allow = false;

        this._confirmContinue(() => {
            this._editMode = event.detail.detail;
        });
    }

    protected _onEditToggle(event: OrAssetViewerEditToggleEvent) {
        this._editMode = event.detail;
        this._updateRoute(true);
    }

    protected _confirmContinue(action: () => void) {
        if (this._viewer.isModified()) {
            showOkCancelDialog(i18next.t("assetModified"), i18next.t("confirmContinueAssetModified"))
                .then((ok) => {
                    if (ok) {
                        action();
                    }
                });
        } else {
            action();
        }
    }

    protected _onAssetAdd(ev: OrAssetTreeAddEvent) {
        // Load the asset into the viewer and ensure we're in edit mode
        this._viewer.asset = ev.detail.asset;
        this._editMode = true;
        this._updateRoute(true);
    }

    protected _onAssetSave(ev: OrAssetViewerSaveEvent) {
        if (ev.detail.success && ev.detail.isNew) {
            this._addedAssetId = ev.detail.asset.id!;
        }
    }

    protected _onAssetTreeAssetEvent(ev: OrAssetTreeAssetEvent) {
        // Check if the new asset just saved has been created in the asset tree and if so select it
        if (ev.detail.cause === AssetEventCause.CREATE && this._addedAssetId) {
            if (this._addedAssetId === ev.detail.asset.id) {
                this._assetIds = [ev.detail.asset.id];
                this._addedAssetId = undefined;
            }
        }
    }

    protected _updateRoute(silent: boolean = true) {
        if (silent) {
            router.pause();
        }
        const assetId = this._assetIds && this._assetIds.length === 1 ? this._assetIds[0] : undefined;
        router.navigate(getAssetsRoute(this._editMode, assetId));
        if (silent) {
            window.setTimeout(() => {
                router.resume();
            }, 0);
        }
    }
}
