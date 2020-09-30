import {css, customElement, html, LitElement, property, query, unsafeCSS} from "lit-element";
import {AgentDescriptor, Asset, AssetDescriptor} from "@openremote/model";
import "@openremote/or-input";
import {AssetTreeConfig} from "./index";
import {
    createListGroup,
    ListGroupItem,
    ListItem,
    OrMwcList,
    OrMwcListChangedEvent
} from "@openremote/or-mwc-components/dist/or-mwc-list";
import {OrMwcDialog} from "@openremote/or-mwc-components/dist/or-mwc-dialog";
import {i18next} from "@openremote/or-translate";
import {Util, DefaultColor5, DefaultColor2} from "@openremote/core";
import { InputType, OrInputChangedEvent, OrInput } from "@openremote/or-input";

export type OrAddAssetDetail = {
    name: string | undefined;
    descriptor: AssetDescriptor | AgentDescriptor | undefined;
};

export class OrAddChangedEvent extends CustomEvent<OrAddAssetDetail> {

        public static readonly NAME = "or-add-asset-changed";

    constructor(addAssetDetail: OrAddAssetDetail) {
        super(OrAddChangedEvent.NAME, {
            bubbles: true,
            composed: true,
            detail: addAssetDetail
        });
    }
}

declare global {
    export interface HTMLElementEventMap {
        [OrAddChangedEvent.NAME]: OrAddChangedEvent;
    }
}

@customElement("or-add-asset-dialog")
export class OrAddAssetDialog extends LitElement {

    @property({attribute: false})
    public config!: AssetTreeConfig;

    @property({attribute: false})
    public agentTypes!: AgentDescriptor[];

    @property({attribute: false})
    public assetTypes!: AssetDescriptor[];

    @property({attribute: false})
    public parent?: Asset;

    @property({attribute: false})
    public selectedType?: AgentDescriptor | AssetDescriptor;

    public name: string = "New Asset";

    @query("#name-input")
    protected nameInput!: OrInput;

    @query("#agent-list")
    protected agentList?: OrMwcList;

    @query("#asset-list")
    protected assetList?: OrMwcList;

    public static get styles() {
        // language=CSS
        return css`
            #name-input {
                margin: 10px;
            }
            #mdc-dialog-form-add {
                display: flex;
                height: 600px;
                width: 800px;
                border: 1px solid var(--or-app-color5, ${unsafeCSS(DefaultColor5)});
            }
            #asset-type-option-container {
                padding: 15px;
                flex: 1 1 auto;
                max-width: 100%;
                font-size: 16px;
                background-color: var(--or-app-color2, ${unsafeCSS(DefaultColor2)});
            }
            #type-list {
                padding-left: 10px;
                width: 260px;
                overflow: auto;
                text-transform: capitalize;
                border-right: 1px solid var(--or-app-color5, ${unsafeCSS(DefaultColor5)});
            }
            #name-input {
                width: 400px;
            }
        `;
    }

    protected render() {

        const mapDescriptors: (descriptors: (AssetDescriptor | AgentDescriptor)[]) => ListItem[] =
            (descriptors) =>
                descriptors.map((descriptor) => {
                    return {
                        styleMap: {
                            "--or-icon-fill": descriptor.color ? "#" + descriptor.color : "unset"
                        },
                        icon: descriptor.icon,
                        text: Util.getAssetTypeLabel(descriptor),
                        value: descriptor.type!,
                        data: descriptor
                    }
                }).sort(Util.sortByString((listItem) => listItem.text));

        const agentItems = mapDescriptors(this.agentTypes);
        const assetItems = mapDescriptors(this.assetTypes);
        const lists: ListGroupItem[] = [];

        if (agentItems.length > 0) {
            lists.push(
                {
                    heading: i18next.t("agents"),
                    list: html`<or-mwc-list @or-mwc-list-changed="${(evt: OrMwcListChangedEvent) => {if (evt.detail.length === 1) this.onTypeChanged(true, evt.detail[0] as ListItem); }}" .listItems="${agentItems}" id="agent-list"></or-mwc-list>`
                }
            );
        }
        if (assetItems.length > 0) {
            lists.push(
                {
                    heading: i18next.t("assets"),
                    list: html`<or-mwc-list @or-mwc-list-changed="${(evt: OrMwcListChangedEvent) => {if (evt.detail.length === 1) this.onTypeChanged(false, evt.detail[0] as ListItem); }}" .listItems="${assetItems}" id="asset-list"></or-mwc-list>`
                }
            );
        }

        return html`
            <or-input id="name-input" .type="${InputType.TEXT}" min="1" max="1023" comfortable required outlined .label="${i18next.t("name")}" .value="${this.name}" @or-input-changed="${(e: OrInputChangedEvent) => this.onNameChanged(e.detail.value)}"></or-input>
            <form id="mdc-dialog-form-add">
                <div id="type-list">
                    ${createListGroup(lists)}
                </div>
                <div id="asset-type-option-container">
                    ${!this.selectedType 
                    ? html`` 
                    : html`
                        <or-icon id="type-icon" .icon="${this.selectedType.icon}"></or-icon>
                        <or-translate id="type-description" .value="${Util.getAssetTypeLabel(this.selectedType)}"></or-translate>
                    `}
                </div>
            </form>
        `;
    }

    protected onTypeChanged(isAgent: boolean, listItem: ListItem) {
        const descriptor = listItem.data as AssetDescriptor | AgentDescriptor;
        this.selectedType = descriptor;
        const otherList = isAgent ? this.assetList : this.agentList;
        if (otherList) {
            otherList.values = undefined;
        }
        this.onModified();
    };

    protected onNameChanged(name: string) {
        this.name = name;
        this.onModified();
    }

    protected onModified() {
        if (this.nameInput.valid && this.selectedType) {
            this.dispatchEvent(new OrAddChangedEvent({
                name: this.name,
                descriptor: this.selectedType
            }));
        }
    }
}
