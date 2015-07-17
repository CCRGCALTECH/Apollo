

## Architecture notes

### Overview

![](architecture2.png)

[PDF schema](https://github.com/GMOD/Apollo/blob/master/doc/schemaupdates.pdf)

The main components are:

- Grails Server (formally a simple servlet 3 container)
- Datastore: configured via Hibernate / Grails . . can use most anything supported by JDBC / hibernate as well as MongoDB (and possibly ElasticSearch) . . in theory
- JBrowse / Apollo Plugin: JS / HTML5
- GWT client: provides the sidebar.   Can be written in another front-end language, as well.


### Basic concepts

- Grails code is in normal grails directories under "grails-app"
- GWT-only code is under src/gwt except
    - Code shared between the client and the server is under src/gwt/org/bbop/apollo/gwt/shared
- Client code is under client (still)
- Tests are under "Test"
- Old (presumably inactive code) is under src/main/webapp
- New source (loaded into VM) is under src/java or src/groovy except for grails specific code.
- Web code (not much) is either under web-app (and where jbrowse is copied) or under grails-app/assets (these are compiled down).
- GWT-specifc CSS can also be found in: src/gwt/org/bbop/apollo/gwt/client/resources/ , but it inherits the CSS on its current page, as well.



#### Grails platform

Official documentation for Grails is here:  http://grails.github.io/grails-doc/2.4.x/

The main components of the Web Apollo application (the four most important are 1 through 4).

1. The domain classes; these are the main objects
2. Controllers, which route those domains and provide URL routes; provides rest services
3. Views: annotator and index and the only ones that matter for Apollo
4. Services: very important because all of the controllers should typically have routes, then particular business logic should go into the service.
5. Configuration files: The grails-app/conf folder contains central conf files, but a apollo-config.groovy file in your root directory can be specified for your data source (i.e. it is not necessary to edit DataSource.groovy)
6. Grails-app/assets: all your javascript live here. efficient way to deliver this stuff
7. Resources: web-app directory: css, images, and the jbrowse directory + WA plugin are initialized here.
8. Client directory: The WA plugin is developed in this folder before it is dynamically loaded into the web-app directory

In Grails views ...

- Most of Views are under grails-app
  - everything conforms to the MVC backend model for the Grails application. 
- Most of java, css, html is under web-app directory
  - Application logic for groovy, gwt, java, etc live here. we could put our old servlets there, but not recommended. 

### Schema/domain classes

Domain classes: the most important domain class everywhere is the Feature; it is the key to everything that we do. The way a domain class is built (Nathan cleaning as he explains): 
The main class represents a database table, the way it works with "Feature", which is inherited by many other classes. All features are stored in the same table, the differences in SQL, there is a class table and when it pulls these tables from the database --- it queries it and then pulls it (converts it) into the right class.
There are a number of constrains you can set. 

Very important: the hasMany maps the one-to-many relationship within the database. It can have many locations. the parentFeatureRelationships is where you map this one-to-many relationship. 
You also have to have a single item relationship.

You can add any methods you want to.  Nathan tried to not add any methods, except things for FeatureLocation. things that are obvious, but not of any sufficient calculation. 

auditable = true means that a new table, a feature auditing tool, is keeping track of history for the specified objects

#### Feature class

all features inherit an ontologyId and specifies a cvTerm
Nathan trying to use ontologyId everywhere he can, and also conserving cvTerm for those places where ontologyId is not present. 

"Feature" is too generic, for example, so it does not have an ontologyId


#### Sequence class

Sequences are the method for WA to grabs sequences
used to have a cache built-in mechanism
doesn't want to have that anymore to avoid running into memory problems.


#### Feature locations

Features such as genes all have a feature location belongs to a particular sequence. If you have a feature with subclasses, it can exist within many locations, and each location belongs to its own sequence.

#### Feature relationship

Feature relationships can define parent/child relationships as well as SO terms i.e. SO "part_of" relationships

#### Feature enums

The FeatureString enum: allows for mapping names for concepts, and it is useful to use these enums without worrying about string mappings inside the application.


### Running the application

If you go through and run this grails application when you send the URL request, then methods that are sent through the AnnotationEditorController  (formerly called AnnotationEditorService) dynamically calls a method using handleOperation.

The AnnotatorController serves the page that the annotator is on. This doesn't map to a particular domain object.

In most cases when we have these methods, it unwraps the data that is sent through into JSON object as a set of variables. Then it is processed into java objects and routed back to JSON to send back. 

How do you handle the collaborative aspect, like adding a transcript? 
When annotator creates a transcript, it is then released to requestHandlingService 
it sends it to an annotation event, which sends it to a WebSocket, and it's then broadcasted to everyone

#### Create socket and listener
Subscribe to all topics and AnnotationNotification that come
If add transcript operation occurs, this is broadcasted locally.
Then it does a JSON roundtrip to render
while it is in there it also sends the return object that belongs to an AnnotationEvent
 
Procedure transcript is created --> goes to the server --> adds a transcript locally --> announces is to everyone via the client

We used to use long polling before websockets. now Spring uses a SockJS protocol/websockets  is in charge of sending the message.

brokerMessagingTemplate is the converter to broadcast the event


#### Controllers
Route url and info to methods


#### Services
Classes that perform business logic
(In IntelliJ, these are indicated by green buttons on the definitions to show that these are Injected Spring Bean classes)

The word @Transactional means that every operation that is not private is handled via a transaction. In the old model there were a lot of files that were recreated each time, even though they did the same. Now we define a class and can use it again and again. And there can be transactions within transaction. I could also call other services within services.

addTranscript 
generateTranscript

The different services do exactly what their name implies. It may not always be clear in what particular service each class should be in, but it can be changed later. It is easy also to make changes to the names as well. 


#### Views

TODO





### Main Configuration

The central configuration files are defined in grails-app/conf/ folder, however the user normally only edits their personal config in apollo-config.groovy. That is because the user config file will override those in the central configuration. See [Configure.md](Configure.md) for details.

#### Database configuration


#####grails-app/conf/DataSource.groovy


The database configuration is normally specified by the user in apollo-config.groovy. It is recommended to take sample-postgres-apollo-config.groovy or sample-mysql-apollo-config.groovy and copy it to apollo-config.groovy



The default database driver is the h2 database, which is an "embedded" database that doesn't require installing postgres or mysql. It is not generally seen as performant as postgres or mysql though. 


Note: there are three environments that can be setup: a development environment, a test environment, and a production environment, and these are basically assigned automatically depending on how you deploy the app.

* Development environment - "apollo run-local" or "apollo debug"

* Test environment - "apollo test"

* Production environment - "apollo deploy" or "apollo release"


Note: If there are no users and no annotations, a bootstrap procedure can also automatically create some annotations and users to start up the app so there is something in there to begin with.


#### UrlMapping configuration:

#####grails-app/conf/UrlMappings.groovy

The UrlMappings sets up a mapping from routes to controllers

Standard and customized mappings go in here. The way we route jbrowse to organism data directories is also controlled here.  The organismJBrowseDirectory is set for a particular session, per user. If none specified, it brings up a default one. 


#### Build configuration

#####grails-app/conf/BuildConfig.groovy

If there are libraries that are missing are are to be added, you can add them here.

Additionally, the build system uses the "apollo" script and the "build.xml" to control the compilation and resource steps.


#### Central config

#####grails-app/conf/Config.groovy


The central Grails config contains logging, app config, and also can reference external configs. The external config can override settings without even touching the application code using this method

In our application, we use the apollo-config.groovy then everything in there supersedes this file.

The log4j area can enable logging levels. You can turn on the "debug grails.app" to output all the webapollo debug info, or also set the "grails.debug" environment variable for java too.

There is also some WebApollo configuration here, and it is mostly covered by the [configuration section](Configure.md).



### GWT web-app

When GWT compiles, it loads files into the web-app directory. When it loads up annotator, it goes to annotator index (the way things get loaded) it does an include annotator.nocache.js file, and with that, it includes all GWT stuff for the /annotator/index route. The src/gwt/org/bbop/apollo/gwt/ contains much code and the src/gwt/org/bbop/apollo/gwt/Annotator.gwt.xml is a central config file for the GWT web-app.



#### User interface definitions

A Bootstrap/GWT interface handles the tabs on the right for the new UI.
The annotator object is at the root of everything.

Example definition:
MainPanel.ui.xml 


### Tests

#### Unit tests

Unit tests and some basic javascript tests are running on Travis-CI (see .travis.yml for example script).

You can also run "apollo test" to run the tests locally. It will use the "test" database configuration automatically. 




