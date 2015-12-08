define([
         'dojo/_base/declare',
         'dojo/_base/lang',
         'dojo/_base/array',
         'dojo/mouse',
         'dojo/dom-construct',
         'dojo/query',
         'dojo/dom',
         'dojo/dom-style',
         'dojo/dom-class',
         'dojo/on',
         'dijit/Menu',
         'dijit/MenuItem',
         'jquery',
         'JBrowse/View/Track/Sequence',
         'JBrowse/Util',
         'WebApollo/JSONUtils',
         'WebApollo/Permission',
         'WebApollo/Store/SeqFeature/ScratchPad',
         'dojo/request/xhr'
],
function(declare,
         lang,
         array,
         mouse,
         domConstruct,
         query,
         dom,
         domStyle,
         domClass,
         on,
         Menu,
         MenuItem,
         $,
         Sequence,
         Util,
         JSONUtils,
         Permission,
         ScratchPad,
         xhr) {

return declare( Sequence,
{
    /**
     * Track to display the underlying reference sequence, when zoomed in
     * far enough.
     */
    constructor: function( args ) {
        this.context_path = "..";
        this.annotationPrefix = "Annotations-";
        this.loadTranslationTable();
        this.loadSequenceAlterations();
        this.annotStoreConfig=lang.mixin(lang.clone(this.config),{browser:this.browser,refSeq:this.refSeq});
        this.alterationsStore = new ScratchPad(this.annotStoreConfig);
        this.browser.getPlugin( 'WebApollo', dojo.hitch( this, function(p) {
            this.webapollo = p;
        }));
    },
    _defaultConfig: function() {
        var thisConfig = this.inherited(arguments);
        thisConfig.pinned = true;
        return thisConfig;
    },

    /** removing "Pin to top" menuitem, so SequenceTrack is always pinned 
     *    and "Delete track" menuitem, so can't be deleted
     *   (very hacky since depends on label property of menuitem config)
     */
    _trackMenuOptions: function() {
        var options = this.inherited( arguments );
        options = this.removeItemWithLabel(options, "Pin to top");
        options = this.removeItemWithLabel(options, "Delete track");
        return options;
    },
    removeItemWithLabel: function(inarray, label) {
        return array.filter(inarray,function(obj) {
            return ! (obj.label && (obj.label === label));
        });
    },

    /*
     *  sequence alteration UPDATE command received by a ChangeNotificationListener
     *  currently handled as if receiving DELETE followed by ADD command
     */
    annotationsUpdatedNotification: function(annots)  {
        this.annotationsDeletedNotification(annots);
        this.annotationsAddedNotification(annots);
    },
    annotationsAddedNotification: function(responseFeatures)  {
        for (var i = 0; i < responseFeatures.length; ++i) {
            var feat = JSONUtils.createJBrowseSequenceAlteration( responseFeatures[i] );
            var id = responseFeatures[i].uniquename;
            if (! this.alterationsStore.getFeatureById(id))  {
                this.alterationsStore.insert(feat);
            }
        }
        this.changed();
    },
    /**
     * sequence alteration annotation DELETE command received by a ChangeNotificationListener,
     *      so telling SequenceTrack to remove from it's SeqFeatureStore
     */
    annotationsDeletedNotification: function(annots)  {
        for (var i = 0; i < annots.length; ++i) {
            var id_to_delete = annots[i].uniquename;
            this.alterationsStore.deleteFeatureById(id_to_delete);
        }
        this.changed();
    },
    requestDeletion: function(selected)  {
        var features = [{uniquename: selected.id()}];
        var postData = {
            "track": this.annotationPrefix+this.refSeq.name,
            "features": features,
            "operation": "delete_sequence_alteration"
        };
        xhr(this.context_path + "/AnnotationEditorService", {
            handleAs: "json",
            data: JSON.stringify(postData),
            method: "post"
        }).then(function(response) {
            // Success
        }, function(response) {
            console.log("Error",response);
        });
    },
    
    storedFeatureCount: function(start, end)  {
        var track = this;
        if (start == undefined) {
            start = track.refSeq.start;
        }
        if (end == undefined) {
            end = track.refSeq.end;
        }
        var count = 0;
        track.alterationsStore.getFeatures({ ref: track.refSeq.name, start: start, end: end}, function() { count++; });
        
        return count;
    }, 
    fillBlock: function(args) {
        var thisB=this;
        var supermethod = this.getInherited(arguments);
        var finishCallback=args.finishCallback;
        var alterations=[];
        var leftBase=args.leftBase;
        var rightBase=args.rightBase;
        var scale=args.scale;
        this.alterationsStore.getFeatures({start: args.leftBase, end: args.rightBase-1 },function(f) { alterations.push(f); });
        if(scale<1.3) {
            this.hide();
            return;
        }
        else {
            this.show();
        }
        args.finishCallback=function() {
            finishCallback();
            // Add right-click menu
            // Add mouseover highlight
            var nl=query('.base',args.block.domNode);
            if(!nl.length) return;

            thisB.renderAlterations(alterations,leftBase,rightBase,args.block.domNode,scale);

            // render mouseover highlight
            nl.on(mouse.enter,function(evt) {
                domClass.add(evt.target,"highlighted_base");
            });
            nl.on(mouse.leave,function(evt) {
                domClass.remove(evt.target,"highlighted_base");
            });
            nl.forEach(function( featDiv ) {
                var refreshMenu = lang.hitch( thisB, '_refreshMenu', featDiv );
                thisB.own( on( featDiv,  'mouseover', refreshMenu ) );
            });
        };
        supermethod.call(this,args);
    },

    _refreshMenu: function( featDiv ) {
        // if we already have a menu generated for this feature,
        // give it a new lease on life
        if( ! featDiv.contextMenu ) {
            featDiv.contextMenu = this._makeFeatureContextMenu( featDiv, this.config.menuTemplate );
        }

        // give the menu a timeout so that it's cleaned up if it's not used within a certain time
        if( featDiv.contextMenuTimeout ) {
            window.clearTimeout( featDiv.contextMenuTimeout );
        }
        var timeToLive = 30000; // clean menus up after 30 seconds
        featDiv.contextMenuTimeout = window.setTimeout( function() {
            if( featDiv.contextMenu ) {
                featDiv.contextMenu.destroyRecursive();
                Util.removeAttribute( featDiv, 'contextMenu' );
            }
            Util.removeAttribute( featDiv, 'contextMenuTimeout' );
        }, timeToLive );
    },
    _makeFeatureContextMenu: function( featDiv,container ) {
        var thisB=this;
        var menu=new Menu();
        var feature = featDiv.feature;
        var hasPermission=this.webapollo.annotService.hasWritePermission()
        this.own( menu );
        if(feature) {
            
            menu.addChild(new MenuItem({
                label: "View details",
                iconClass: "dijitIconTask",
                onClick: function(evt) {
                    console.log(featDiv.feature);
                    thisB._openDialog({
                        action: "contentDialog",
                        title: "Sequence alteration",
                        content: domConstruct.create('div',{innerHTML: '<p>'+feature.get('id')+'</p>'+feature.get('residues')?('<p>'+feature.get('residues')+'</p>'):''})
                    },evt,featDiv);
                }
            }));
            if(hasPermission) {
                menu.addChild(new MenuItem({
                    label: "Remove "+featDiv.feature.get("type"),
                    iconClass: "dijitIconDelete",
                    onClick: function(evt) {
                        thisB.requestDeletion(featDiv.feature);
                    }
                }));
            }
        }
        else {
            if(hasPermission) {
                menu.addChild(new MenuItem({
                    label: "Create insertion",
                    iconClass: "dijitIconNewTask",
                    onClick: function(evt){
                        var gcoord = Math.floor(thisB.browser.view.absXtoBp(evt.pageX));
                        thisB.createGenomicInsertion(evt,gcoord-1);
                    }
                }));
                menu.addChild(new MenuItem({
                    label: "Create deletion",
                    iconClass: "dijitIconDelete",
                    onClick: function(evt){
                        var gcoord = Math.floor(thisB.browser.view.absXtoBp(evt.pageX));
                        thisB.createGenomicDeletion(evt,gcoord-1);
                    }
                }));
                menu.addChild(new MenuItem({
                    label: "Create substitution",
                    iconClass: "dijitIconEditProperty",
                    onClick: function(evt){
                        var gcoord = Math.floor(thisB.browser.view.absXtoBp(evt.pageX));
                        thisB.createGenomicSubstitution(evt,gcoord-1);
                    }
                }));
            }
        }
        menu.startup();
        menu.bindDomNode( featDiv );
    },

    createAddSequenceAlterationPanel: function(type, gcoord) {
        var track = this;
        var content = dojo.create("div");
        var charWidth = 15;
        if (type == "deletion") {
            var deleteDiv = dojo.create("div", { }, content);
            var deleteLabel = dojo.create("label", { innerHTML: "Length", className: "sequence_alteration_input_label" }, deleteDiv);
            var deleteField = dojo.create("input", { type: "text", size: 10, className: "sequence_alteration_input_field" }, deleteDiv);

            $(deleteField).keydown(function(e) {
                var unicode = e.charCode || e.keyCode;
                var isBackspace = (unicode == 8);  // 8 = BACKSPACE
                if (unicode == 13) {  // 13 = ENTER/RETURN
                    addSequenceAlteration();
                }
                else {
                    var newchar = String.fromCharCode(unicode);
                    // only allow numeric chars and backspace
                    if (! (newchar.match(/[0-9]/) || isBackspace))  {  
                        return false; 
                    }
                }
            });
        }
        else {
            var plusDiv = dojo.create("div", { }, content);
            var minusDiv = dojo.create("div", { }, content);
            var plusLabel = dojo.create("label", { innerHTML: "+ strand", className: "sequence_alteration_input_label" }, plusDiv);
            var plusField = dojo.create("input", { type: "text", size: charWidth, className: "sequence_alteration_input_field" }, plusDiv);
            var minusLabel = dojo.create("label", { innerHTML: "- strand", className: "sequence_alteration_input_label" }, minusDiv);
            var minusField = dojo.create("input", { type: "text", size: charWidth, className: "sequence_alteration_input_field" }, minusDiv);
            $(plusField).keydown(function(e) {
                var unicode = e.charCode || e.keyCode;
                // ignoring delete key, doesn't do anything in input elements?
                var isBackspace = (unicode == 8);  // 8 = BACKSPACE
                if (unicode == 13) {  // 13 = ENTER/RETURN
                    addSequenceAlteration();
                }
                else {
                    var curval = e.currentTarget.value;
                    var newchar = String.fromCharCode(unicode);
                    // only allow acgtnACGTN and backspace
                    //    (and acgtn are transformed to uppercase in CSS)
                    if (newchar.match(/[acgtnACGTN]/) || isBackspace)  {  
                        // can't synchronize scroll position of two input elements, 
                        // see http://stackoverflow.com/questions/10197194/keep-text-input-scrolling-synchronized
                        // but, if scrolling triggered (or potentially triggered), can hide other strand input element
                        // scrolling only triggered when length of input text exceeds character size of input element
                        if (isBackspace)  {
                            minusField.value = Util.complement(curval.substring(0,curval.length-1));  
                        }
                        else {
                            minusField.value = Util.complement(curval + newchar);  
                        }
                        if (curval.length > charWidth) {
                            $(minusDiv).hide();
                        }
                        else {
                            $(minusDiv).show();  // make sure is showing to bring back from a hide
                        }
                    }
                    else { return false; }  // prevents entering any chars other than ACGTN and backspace
                }
            });

            $(minusField).keydown(function(e) {
                var unicode = e.charCode || e.keyCode;
                // ignoring delete key, doesn't do anything in input elements?
                var isBackspace = (unicode == 8);  // 8 = BACKSPACE
                if (unicode == 13) {  // 13 = ENTER
                    addSequenceAlteration();
                }
                else {
                    var curval = e.currentTarget.value;
                    var newchar = String.fromCharCode(unicode);
                    // only allow acgtnACGTN and backspace
                    //    (and acgtn are transformed to uppercase in CSS)
                    if (newchar.match(/[acgtnACGTN]/) || isBackspace)  {  
                        // can't synchronize scroll position of two input elements, 
                        // see http://stackoverflow.com/questions/10197194/keep-text-input-scrolling-synchronized
                        // but, if scrolling triggered (or potentially triggered), can hide other strand input element
                        // scrolling only triggered when length of input text exceeds character size of input element
                        if (isBackspace)  {
                            plusField.value = Util.complement(curval.substring(0,curval.length-1));  
                        }
                        else {
                            plusField.value = Util.complement(curval + newchar);  
                        }
                        if (curval.length > charWidth) {
                            $(plusDiv).hide();
                        }
                        else {
                            $(plusDiv).show();  // make sure is showing to bring back from a hide
                        }
                    }
                    else { return false; }  // prevents entering any chars other than ACGTN and backspace
                }
            });

        }
        var buttonDiv = dojo.create("div", { className: "sequence_alteration_button_div" }, content);
        var addButton = dojo.create("button", { innerHTML: "Add", className: "sequence_alteration_button" }, buttonDiv);

        var addSequenceAlteration = function() {
            var ok = true;
            var inputField;
            var inputField = ((type == "deletion") ? deleteField : plusField);
            // if (type == "deletion") { inputField = deleteField; }
            // else  { inputField = plusField; }
            var input = inputField.value.toUpperCase();
            if (input.length == 0) {
                alert("Input cannot be empty for " + type);
                ok = false;
            }
            if (ok) {
                var input = inputField.value.toUpperCase();
                if (type == "deletion") {
                    if (input.match(/\D/)) {
                        alert("The length must be a number");
                        ok = false;
                    }
                    else {
                        input = parseInt(input);
                        if (input <= 0) {
                            alert("The length must be a positive number");
                            ok = false;
                        }
                    }
                }
                else {
                    if (input.match(/[^ACGTN]/)) {
                        alert("The sequence should only containg A, C, G, T, N");
                        ok = false;
                    }
                }
            }
            if (ok) {
                var fmin = gcoord;
                var fmax;
                if (type == "insertion") {
                    fmax = gcoord;
                }
                else if (type == "deletion") {
                    fmax = gcoord + parseInt(input);
                }
                else if (type == "substitution") {
                    fmax = gcoord + input.length;;
                }
                if (track.storedFeatureCount(fmin, fmax == fmin ? fmin + 1 : fmax) > 0) {
                    alert("Cannot create overlapping sequence alterations");
                }
                else {
                    var feature = { "location": {
                        "fmin": fmin,
                        "fmax": fmax,
                        "strand": 1
                    },"type": {
                        "name":type,
                        "cv": {
                            "name":"sequence"
                        }
                    } };
                    if (type != "deletion") {
                        feature.residues= input;
                    }
                    var features = [feature];
                    var postData = {
                        "track": track.annotationPrefix+track.refSeq.name,
                        "features": features,
                        "operation": "add_sequence_alteration"
                    };
                    xhr(track.context_path + "/AnnotationEditorService", {
                        handleAs: "json",
                        data: JSON.stringify(postData),
                        method: "post"
                    }).then(function(response) {
                        // Success
                    }, function(response) {
                        console.log("Error",response);
                    });
                }
            }
        };
        
        dojo.connect(addButton, "onclick", null, function() {
            addSequenceAlteration();
        });

        return content;
    },
    loadTranslationTable: function() {
        var thisB = this;
        return xhr.post( this.context_path + "/AnnotationEditorService",
        {
            data: JSON.stringify({ "track": this.annotationPrefix+this.refSeq.name, "operation": "get_translation_table" }),
            handleAs: "json"
        }).then(function(response) {
            thisB._codonTable=thisB.generateCodonTable(response.translation_table);
            thisB.changed();
            thisB.redraw();
        },
        function(response) {
            console.log('Failed to load translation table. Setting default');
            return response;
        });
    },
    loadSequenceAlterations: function() {
        var track = this;
        return xhr.post( this.context_path + "/AnnotationEditorService",
        {
            data: JSON.stringify({ "track": this.annotationPrefix+this.refSeq.name, "operation": "get_sequence_alterations" }),
            handleAs: "json"
        }).then(
            function(response) {
                var responseFeatures = response.features;
                for (var i = 0; i < responseFeatures.length; i++) {
                    var jfeat = JSONUtils.createJBrowseSequenceAlteration(responseFeatures[i]);
                    track.alterationsStore.insert(jfeat);
                }
                track.featureCount = track.storedFeatureCount();
                track.changed();
            },
            function(response) {
                console.log('Failed to load sequence alternations');
                return response;
            });
     },

    createGenomicInsertion: function(evt,gcoord)  {
        this._openDialog({
            action: "contentDialog",
            title: "Add Insertion",
            content: this.createAddSequenceAlterationPanel("insertion", gcoord)
        },evt);
    },

    createGenomicDeletion: function(evt,gcoord)  {
        this._openDialog({
            action: "contentDialog",
            title: "Add Deletion",
            content: this.createAddSequenceAlterationPanel("deletion", gcoord)
        },evt);
    },

    createGenomicSubstitution: function(evt,gcoord)  {
        this._openDialog({
            action: "contentDialog",
            title: "Add Substitution",
            content: this.createAddSequenceAlterationPanel("substitution", gcoord)
        },evt);
    },

    renderAlterations: function(alterations,leftBase,rightBase,blockNode,scale) {
        var thisB=this;
        array.forEach(alterations,function(alt) {
            var start=alt.get("start");
            var end=alt.get("end");
            var type=alt.get("type");
            var relStart=start-leftBase;
            var relEnd=end-leftBase;
            var pct=relStart*100/(rightBase-leftBase);
            var featDiv;

            if(type=="insertion") {
                featDiv=domConstruct.create("div",{ 
                    "class": "sequence_alteration insertion",
                    "style": {
                        "position": "absolute",
                        "z-index": 20,
                        "top": "0px",
                        "width": "2px",
                        "left": pct+"%",
                        "height": "100%"
                    }
                },blockNode);

                var seqNode = domConstruct.create("table", {
                    style: {
                        "width": (alt.get('residues').length+1)*charWidth+"%",
                        "bottom": "0px",
                        "position": "absolute"
                    }
                }, featDiv);

                seqNode.appendChild( thisB._renderSeqTr( alt.get('start'), alt.get('end'), alt.get('residues'), scale ));
            }
            else if(type=="deletion") {
                var charWidth=100/(rightBase-leftBase);
                featDiv=domConstruct.create("div",{ 
                    "class": "sequence_alteration deletion",
                    "style": {
                        "position": "absolute",
                        "z-index": 20,
                        "top": "0px",
                        "width": (end-start)*charWidth+"%",
                        "left": pct+"%",
                        "height": "100%"
                    }
                },blockNode);
                var seqNode = domConstruct.create("table", {
                    style: {
                        width: "100%",
                        bottom: "0px",
                        position: "absolute"
                    }
                }, featDiv);
                seqNode.appendChild( thisB._renderSeqTr( alt.get('start'), alt.get('end'), new Array(alt.get('residues').length+1).join('-'), scale ));
            }
            else if(type=="substitution") {
                var charWidth=100/(rightBase-leftBase);
                featDiv=domConstruct.create("div",{ 
                    "class": "sequence_alteration substitution",
                    "style": {
                        "position": "absolute",
                        "z-index": 20,
                        "top": "0px",
                        "width": (end-start)*charWidth+"%",
                        "left": pct+"%",
                        "height": "100%"
                    }
                },blockNode);

                var seqNode = domConstruct.create("table", {
                    style: {
                        width: "100%",
                        bottom: "0px",
                        position: "absolute"
                    }
                }, featDiv);
                seqNode.appendChild( thisB._renderSeqTr( alt.get('start'), alt.get('end'), alt.get('residues'), scale ));
            }
            var refreshMenu = lang.hitch( thisB, '_refreshMenu', featDiv );
            thisB.own( on( featDiv,  'mouseover', refreshMenu ) );
            featDiv.feature=alt;
        });
    }

 
});
});

