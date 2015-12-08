define( [
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
    'JBrowse/View/Track/Sequence',
    'JBrowse/Util',
    'WebApollo/JSONUtils',
    'WebApollo/Permission',
    'WebApollo/Store/SeqFeature/ScratchPad',
    'dojo/request'
     ],
function(
    declare,
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
    Sequence,
    Util,
    JSONUtils,
    Permission,
    ScratchPad,
    request
     ) {

return declare( Sequence,
{
    /**
     * Track to display the underlying reference sequence, when zoomed in
     * far enough.
     */
    constructor: function( args ) {
        this.context_path = "..";
        this.loadTranslationTable();
        this.loadSequenceAlterations();
        this.annotStoreConfig=lang.mixin(lang.clone(this.config),{browser:this.browser,refSeq:this.refSeq});
        this.alterationsStore = new ScratchPad(this.annotStoreConfig);
    },

    /*
     *  sequence alteration UPDATE command received by a ChangeNotificationListener
     *  currently handled as if receiving DELETE followed by ADD command
     */
    annotationsUpdatedNotification: function(annots)  {
        this.annotationsDeletedNotification(annots);
        this.annotationAddedNotification(annots);
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
        request(this.context_path + "/annotationEditor/deleteSequenceAlternation", {
            handleAs: "json",
            data: {
                track: this.refSeq.name,
                features: features
            },
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
        this.alterationsStore.getFeatures({start: args.leftBase, end: args.rightBase-1 },function(f) { alterations.push(f); }); 
        args.finishCallback=function() {
            finishCallback();
            // Add right-click menu
            // Add mouseover highlight
            var nl=query('.base',args.block.domNode);
            if(!nl.length) return;

            nl.style("backgroundColor","#E0E0E0");
            thisB.renderAlterations(alterations,leftBase,rightBase,args.block.domNode,thisB);

            // render mouseover highlight
            nl.on(mouse.enter,function(evt) {
                evt.target.oldColor=domStyle.get(evt.target,"backgroundColor");
                domStyle.set(evt.target,"backgroundColor","orange");
            });
            nl.on(mouse.leave,function(evt) {
                domStyle.set(evt.target,"backgroundColor",evt.target.oldColor);
                evt.target.oldColor=null
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
        this.own( menu );
        if(featDiv.feature) {
            
            menu.addChild(new MenuItem({
                label: "View "+featDiv.feature.get("type")+" details",
                iconClass: "dijitIconTask",
                onClick: function(evt) {
                    console.log(featDiv.feature);
                    thisB._openDialog({
                        action: "contentDialog",
                        title: "Add Insertion",
                        content: domConstruct.create('p',{innerHTML: featDiv.feature.data.id})
                    },evt,featDiv);
                }
            }));
            menu.addChild(new MenuItem({
                label: "Remove "+featDiv.feature.get("type"),
                iconClass: "dijitIconDelete",
                onClick: function(evt) {
                    thisB.requestDeletion(featDiv.feature);
                }
            }));
        }
        else {
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
            var inputField = ((type == "deletion") ? deleteField : plusField);
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
                    fmax = gcoord + input.length;
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
                    request(track.context_path + "/annotationEditor", {
                        handleAs: "json",
                        data: {
                            track: track.refSeq.name,
                            features: features,
                            operation: "add_sequence_alteration"
                        },
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
        return request( this.context_path + "/annotationEditor/getTranslationTable",
        {
            data: {
                track: this.refSeq.name
            },
            handleAs: "json",
            method: "post"
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
        return request( this.context_path + "/annotationEditor/getTranslationTable",{
            data: {
                track: this.refSeq.name,
            },
            handleAs: "json",
            method: "post"
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

    renderAlterations: function(alterations,leftBase,rightBase,blockNode,thisB) {
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
                    "style": {
                        "position": "absolute",
                        "z-index": 20,
                        "top": "0px",
                        "width": "2px",
                        "left": pct+"%",
                        "backgroundColor": "rgba(0,255,0,0.2)",
                        "height": "100%"
                    }
                },blockNode);
            }
            else if(type=="deletion") {
                var charWidth=100/(rightBase-leftBase);
                featDiv=domConstruct.create("div",{ 
                    "style": {
                        "position": "absolute",
                        "z-index": 20,
                        "top": "0px",
                        "width": (end-start)*charWidth+"%",
                        "left": pct+"%",
                        "backgroundColor": "rgba(255,0,0,0.2)",
                        "height": "100%"
                    }
                },blockNode);
            }
            else if(type=="substitution") {
                var charWidth=100/(rightBase-leftBase);
                featDiv=domConstruct.create("div",{ 
                    "style": {
                        "position": "absolute",
                        "z-index": 20,
                        "top": "0px",
                        "width": (end-start)*charWidth+"%",
                        "left": pct+"%",
                        "backgroundColor": "rgba(255,255,0,0.2)",
                        "height": "100%"
                    }
                },blockNode);
            }
            var refreshMenu = lang.hitch( thisB, '_refreshMenu', featDiv );
            thisB.own( on( featDiv,  'mouseover', refreshMenu ) );
            featDiv.feature=alt;
        });
    }

 
});
});
