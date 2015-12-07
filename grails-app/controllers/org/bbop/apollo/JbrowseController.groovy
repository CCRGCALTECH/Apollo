package org.bbop.apollo

import grails.converters.JSON
import liquibase.util.file.FilenameUtils
import org.bbop.apollo.gwt.shared.FeatureStringEnum
import org.bbop.apollo.projection.DiscontinuousChunkProjector
import org.bbop.apollo.projection.MultiSequenceProjection
import org.bbop.apollo.projection.ProjectionChunk
import org.bbop.apollo.projection.RefSeqProjector
import org.bbop.apollo.sequence.Range
import org.codehaus.groovy.grails.web.json.JSONObject
import org.codehaus.groovy.grails.web.json.JSONArray

import javax.servlet.http.HttpServletResponse
import java.text.DateFormat
import java.text.SimpleDateFormat
import static org.springframework.http.HttpStatus.*

class JbrowseController {

    private static final int DEFAULT_BUFFER_SIZE = 10240; // ..bytes = 10KB.

    def grailsApplication
    def sequenceService
    def permissionService
    def preferenceService
    def servletContext
    def projectionService
    def trackService
    def trackMapperService

    // TODO: move to a service instead?
    RefSeqProjector refSeqProjector = new RefSeqProjector()

    def chooseOrganismForJbrowse() {
        [organisms: Organism.findAllByPublicMode(true, [sort: 'commonName', order: 'asc']), flash: [message: params.error]]
    }


    def indexRouter() {
        log.debug "indexRouter ${params}"

        List<String> paramList = new ArrayList<>()
        params.eachWithIndex { entry, int i ->
            if (entry.key != "action" && entry.key != "controller" && entry.key != "organism") {
                paramList.add(entry.key + "=" + entry.value)
            }
        }
        // case 3 - validated login (just read from preferences, then
        if (permissionService.currentUser && params.organism) {
            Organism organism = Organism.findById(params.organism)
            preferenceService.setCurrentOrganism(permissionService.currentUser, organism)
        }

        if (permissionService.currentUser) {
            File file = new File(servletContext.getRealPath("/jbrowse/index.html"))
            render file.text
            return
        }

        // case 1 - anonymous login with organism ID, show organism
        if (params.organism) {
            log.debug "organism ID specified: ${params.organism}"

            // set the organism


            Organism organism = Organism.findByCommonName(params.organism)
            if (!organism && params.organism.isInteger()) {
                organism = Organism.findById(params.organism.toInteger())
            }
            if (!organism) {
                String urlString = "/jbrowse/index.html?${paramList.join("&")}"
                forward(controller: "jbrowse", action: "chooseOrganismForJbrowse", params: [urlString: urlString, error: "Unable to find organism '${params.organism}'"])
            }


            def session = request.getSession(true)
            session.setAttribute(FeatureStringEnum.ORGANISM_JBROWSE_DIRECTORY.value, organism.directory)
            session.setAttribute(FeatureStringEnum.ORGANISM_ID.value, organism.id)
            session.setAttribute(FeatureStringEnum.ORGANISM_NAME.value, organism.commonName)

            // create an anonymous login
            File file = new File(servletContext.getRealPath("/jbrowse/index.html"))
            render file.text
            return
        }

        // case 2 - anonymous login with-OUT organism ID, show organism list
        paramList.add("organism=${params.organism}")
        String urlString = "/jbrowse/index.html?${paramList.join("&")}"
        forward(controller: "jbrowse", action: "chooseOrganismForJbrowse", params: [urlString: urlString])
    }


    private String getJBrowseDirectoryForSession() {
        if (!permissionService.currentUser) {
            return request.session.getAttribute(FeatureStringEnum.ORGANISM_JBROWSE_DIRECTORY.value)
        }

        String organismJBrowseDirectory = preferenceService.currentOrganismForCurrentUser.directory
        if (!organismJBrowseDirectory) {
            for (Organism organism in Organism.all) {
                // load if not
                if (!organism.sequences) {
                    sequenceService.loadRefSeqs(organism)
                }

                if (organism.sequences) {
                    User user = permissionService.currentUser
                    UserOrganismPreference userOrganismPreference = UserOrganismPreference.findByUserAndOrganism(user, organism)
                    List<Sequence> sequences = organism?.sequences
                    JSONArray sequenceArray = new JSONArray()
                    sequences.each {
                        JSONObject jsonObject = new JSONObject()
                        jsonObject.name = it.name
                        sequenceArray.add(jsonObject)
                    }

                    if (userOrganismPreference == null) {
                        Bookmark bookmark = new Bookmark(
                                organism: organism
                                , sequenceList: sequenceArray.toString()
                                , user: user
                        ).save(insert: true, flush: true, failOnError: true)
                        userOrganismPreference = new UserOrganismPreference(
                                user: user
                                , organism: organism
                                , bookmark: bookmark
                                , currentOrganism: true
                        ).save(insert: true, flush: true)
                    } else {
                        userOrganismPreference.bookmark = userOrganismPreference.bookmark
                        userOrganismPreference.currentOrganism = true
                        userOrganismPreference.save()
                    }

                    organismJBrowseDirectory = organism.directory
                    session.setAttribute(FeatureStringEnum.ORGANISM_JBROWSE_DIRECTORY.value, organismJBrowseDirectory)
                    session.setAttribute(FeatureStringEnum.SEQUENCE_NAME.value, sequenceArray.toString())
                    session.setAttribute(FeatureStringEnum.ORGANISM_ID.value, organism.id)
                    session.setAttribute(FeatureStringEnum.ORGANISM.value, organism.commonName)
                    return organismJBrowseDirectory
                }
            }
        }
        return organismJBrowseDirectory
    }

    /**
     * Handles data directory serving for jbrowse
     */
    def data() {
        Organism currentOrganism = preferenceService.currentOrganismForCurrentUser
        String dataDirectory = getJBrowseDirectoryForSession()
        String dataFileName = dataDirectory + "/" + params.path
        String fileName = FilenameUtils.getName(params.path)

        String referer = request.getHeader("Referer")
        String refererLoc = trackService.extractLocation(referer)

        String putativeSequencePathName = trackService.getSequencePathName(dataFileName)
        println "putative sequence path name ${dataFileName} -> ${putativeSequencePathName} "
//        /opt/apollo/honeybee/data//tracks/Official Gene Set v3.2/{"projection":"None", "padding":50, "referenceTrack":"Official Gene Set v3.2", "sequences":[{"name":"Group11.18"},{"name":"Group9.10"}]}/trackData.json
        if (putativeSequencePathName.contains("projection")) {
            if (fileName.endsWith("trackData.json") || fileName.startsWith("lf-")) {

                JSONObject projectionSequenceObject = (JSONObject) JSON.parse(putativeSequencePathName)
                JSONArray sequenceArray = projectionSequenceObject.getJSONArray(FeatureStringEnum.SEQUENCE_LIST.value)
                List<String> sequenceStrings = new ArrayList<>()
                for (int i = 0; i < sequenceArray.size(); i++) {
                    JSONObject sequenceObject = sequenceArray.getJSONObject(i)
                    sequenceStrings.add(sequenceObject.name)
                }

                if (fileName.endsWith("trackData.json")) {
                    JSONObject trackObject = trackService.projectTrackData(sequenceStrings, dataFileName, refererLoc, currentOrganism)
                    response.outputStream << trackObject.toString()
                    return
                } else if (fileName.startsWith("lf-")) {
                    String trackName = projectionService.getTrackName(dataFileName)
                    JSONArray trackArray = trackService.projectTrackChunk(fileName, dataFileName, refererLoc, currentOrganism, trackName)
                    response.outputStream << trackArray.toString()
                    return
                }


            } else if (fileName.endsWith(".txt") && params.path.toString().startsWith("seq")) {
                // Set content size
                // fileName
//                Group1.22-18.txt
                String parentFile = file.parent
                println "HANDINGL SEQ DATA ${fileName}"
                String sequenceName = fileName.split("-")[0]

                // if getting
//                MultiSequenceProjection projection = projectionService.getProjection(preferenceService.currentOrganismForCurrentUser, projectionService.getTrackName(file.absolutePath), sequenceName)
//                ProjectionInterface projection = projectionMap.values().iterator().next().get(sequenceName)
//                MultiSequenceProjection projection = null
//                if(BookmarkService.isProjectionString(refererLoc)){
//
//                    projection = projectionService.getProjection(preferenceService.currentOrganismForCurrentUser, projectionService.getTrackName(file.absolutePath), sequenceName)
//                }
                MultiSequenceProjection projection = projectionService.getProjection(refererLoc, currentOrganism)
//                if (projection) {
                DiscontinuousChunkProjector discontinuousChunkProjector = DiscontinuousChunkProjector.instance
                // TODO: get proper chunk size from the RefSeq
                Integer defaultChunkSize = 20000
                Integer chunkNumber = discontinuousChunkProjector.getChunkNumberFromFileName(fileName)
                println "projected length ${projection.length}"
                println "maping chunk ${chunkNumber} on proj"
                List<Integer> chunks = discontinuousChunkProjector.getChunksForPath(parentFile)
                for (Integer chunk in chunks) {
                    println "chunk ${chunk} / ${chunks.size()}"
                }
                Integer startProjectedChunk = discontinuousChunkProjector.getStartChunk(chunkNumber, defaultChunkSize)
                Integer endProjectedChunk = discontinuousChunkProjector.getEndChunk(chunkNumber, defaultChunkSize)
                println "projected chunk ${startProjectedChunk}::${endProjectedChunk}"
                Integer startOriginal = projection.projectReverseValue(startProjectedChunk)
                Integer endOriginal = projection.projectReverseValue(endProjectedChunk)
                println "original coord values ${startOriginal}::${endOriginal}"

//                    Integer startOriginalChunkNumber = discontinuousChunkProjector.getChunkForCoordinate(startOriginal,defaultChunkSize)
//                    Integer endOriginalChunkNumber = discontinuousChunkProjector.getChunkForCoordinate(endOriginal,defaultChunkSize)
//                    println "original chunk number ${startOriginalChunkNumber}::${endOriginalChunkNumber}"
//                    Integer startOriginalChunkCoordinate = startOriginalChunkNumber * defaultChunkSize
//                    Integer endOriginalChunkCoordinate = (endOriginalChunkNumber+1) * defaultChunkSize
//                    println "original chunk coordinate ${startOriginalChunkCoordinate}::${endOriginalChunkCoordinate}"


                Organism organism = preferenceService.currentOrganismForCurrentUser
                Sequence sequence = Sequence.findByNameAndOrganism(sequenceName, organism)
                println "ffound sequence ${sequence} for org ${organism.commonName}"
                // TODO: fix the offsets here
                String concatenatedSequence = sequenceService.getRawResiduesFromSequence(sequence, startOriginal, endOriginal)
                println "concatenated length ${concatenatedSequence.length()}"

                // re-project
//                    String inputText = concatenatedSequence
//                    String inputText = projection.projectSequence(concatenatedSequence,startOriginal,endOriginal,startOriginal)
                String inputText = concatenatedSequence
                println "return string length ${inputText.length()}"

                // TODO: get chunks needed for cuts . . ..
                // TODO: get substrings from start / end
//                    projection.cutToProjection()

                response.setContentLength((int) inputText.bytes.length);

                // Open the file and output streams
//                    FileInputStream inputStream = new FileInputStream(file);
//                    OutputStream out = response.getOutputStream();

                // Copy the contents of the file to the output stream
//                    byte[] buf = new byte[DEFAULT_BUFFER_SIZE];
//                    int count = 0;
//                    while ((count = inputStream.read(buf)) >= 0) {
//                        out.write(buf, 0, count);
//                    }

                response.outputStream << inputText
                response.outputStream.close()

//                }

            }
        }
        // 1 - if endsWith trackData and the "sequence" contains a projection then
//        if(fileName.endsWith("trackData.json"))
        //  a - extract the trackData (refactor into its own method)
        //  b - merge them together
        //  c - return that

        File file = new File(dataFileName);

//        http://localhost:8080/apollo/jbrowse/index.html?loc=[proj=None,padding=50,sequences=[Group11.18::Group9.10,Group1.1(GB42145-RA)]]%3A-1..-1&highlight=&tracklist=0

        log.debug "processing path ${params.path} -> ${dataFileName}"

        if (!file.exists()) {
            log.warn("File not found: " + dataFileName);
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }



        String mimeType = getServletContext().getMimeType(fileName);
        if (!mimeType) {
            log.debug("No input MIME type of " + fileName);
            if (fileName.endsWith(".json") || params.format == "json") {
                mimeType = "application/json";
                response.setContentType(mimeType);
            } else if (fileName.endsWith(".bam")
                    || fileName.endsWith(".bw")
                    || fileName.endsWith(".bai")
                    || fileName.endsWith(".conf")
            ) {
                mimeType = "text/plain";
            } else if (fileName.endsWith(".tbi")) {
                mimeType = "application/x-gzip";
            } else {
                log.info("Could not get MIME type of " + fileName + " falling back to text/plain");
                mimeType = "text/plain";
            }
            if (fileName.endsWith("jsonz") || fileName.endsWith("txtz")) {
                response.setHeader 'Content-Encoding', 'x-gzip'
            }
        }



        if (isCacheableFile(fileName)) {
            String eTag = createHashFromFile(file);
            String dateString = formatLastModifiedDate(file);

            response.setHeader("ETag", eTag);
            response.setHeader("Last-Modified", dateString);
        }

        String range = request.getHeader("range");
        long length = file.length();
        Range full = new Range(0, length - 1, length);

        List<Range> ranges = new ArrayList<Range>();

        // from http://balusc.blogspot.com/2009/02/fileservlet-supporting-resume-and.html#sublong
        if (range != null) {

            // Range header should match format "bytes=n-n,n-n,n-n...". If not, then return 416.
            if (!range.matches("^bytes=\\d*-\\d*(,\\d*-\\d*)*\$")) {
                response.setHeader("Content-Range", "bytes */" + length); // Required in 416.
                response.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                return;
            } else {
                // If any valid If-Range header, then process each part of byte range.

                if (ranges.isEmpty()) {
                    for (String part : range.substring(6).split(",")) {
                        // Assuming a file with length of 100, the following examples returns bytes at:
                        // 50-80 (50 to 80), 40- (40 to length=100), -20 (length-20=80 to length=100).
                        long start = sublong(part, 0, part.indexOf("-"));
                        long end = sublong(part, part.indexOf("-") + 1, part.length());

                        if (start == -1) {
                            start = length - end;
                            end = length - 1;
                        } else if (end == -1 || end > length - 1) {
                            end = length - 1;
                        }

                        // Check if Range is syntactically valid. If not, then return 416.
                        if (start > end) {
                            response.setHeader("Content-Range", "bytes */" + length); // Required in 416.
                            response.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                            return;
                        }
                        ranges.add(new Range(start, end, length));
                    }
                }
            }

        }

        response.setContentType(mimeType);
        if (ranges.isEmpty() || ranges.get(0) == full) {
            // Set content size
//            response.setContentLength((int) file.length());

            if (fileName.endsWith(".json") || params.format == "json") {
//            [{"length":1382403,"name":"Group1.1","seqChunkSize":20000,"end":1382403,"start":0},{"length":1405242,"name":"Group1.10","seqChunkSize":20000,"end":1405242,"start":0},{"length":2557,"name":"Group1.11","seqChunkSize":20000,"end":2557,"start":0},
                // this returns ALL of the sequences . . but if we project, we'll want to grab only certain ones
                if (fileName.endsWith("refSeqs.json")) {
                    JSONArray refSeqJsonObject = new JSONArray(file.text)
                    // TODO: it should look up the OGS track either default or variable
//                    if (projectionService.hasProjection(preferenceService.currentOrganismForCurrentUser,projectionService.getTrackName(file.absolutePath))) {
                    println "refseq size ${refSeqJsonObject.size()}"

                    MultiSequenceProjection projection = projectionService.getProjection(refererLoc, currentOrganism)

                    // returns projection to a string of some sort
                    String results = refSeqProjector.projectTrack(refSeqJsonObject, projection, currentOrganism, refererLoc)
                    response.outputStream << results
                } else {
                    // Set content size
                    response.setContentLength((int) file.length());

                    // Open the file and output streams
                    FileInputStream inputStream = new FileInputStream(file);
                    OutputStream out = response.getOutputStream();

                    // Copy the contents of the file to the output stream
                    byte[] buf = new byte[DEFAULT_BUFFER_SIZE];
                    int count = 0;
                    while ((count = inputStream.read(buf)) >= 0) {
                        out.write(buf, 0, count);
                    }
                    inputStream.close();
                    out.close();
                }
            } else if (fileName.endsWith(".txt") && params.path.toString().startsWith("seq")) {
//                else {
                response.setContentLength((int) file.length());

                // Open the file and output streams
                FileInputStream inputStream = new FileInputStream(file);
                OutputStream out = response.getOutputStream();

                // Copy the contents of the file to the output stream
                byte[] buf = new byte[DEFAULT_BUFFER_SIZE];
                int count = 0;
                while ((count = inputStream.read(buf)) >= 0) {
                    out.write(buf, 0, count);
                }
                inputStream.close();
                out.close();
//                }
            }
//            else if (fileName.endsWith(".bai")) {
//                println "processing bai file"
//
//                // TODO: read in . . . write out another one to process . . . which will be alternate index?
//                // file, index file, etc. etc. etc.
//                // generate the BAM
//                if (projectionMap) {
//                    // TODO: implement
////                    String bamfileName = findBamFileName(fileName)
////                    File bamFile = new File(bamfileName)
////                    File newIndexFile = new File(generateBamIndexFileForProjection())
//////                    BAMIndexer.createIndex(new SAMFileReader(bamFile),newIndexFile)
////                    ProjectionBAMIndexer.createIndex(new SAMFileReader(bamFile),newIndexFile)
////                    BAMFileReader reader = new BAMFileReader(bamFile,file,false)
//                }
//
////                SAMFileReader samFileReader = new SAMFileReader()
//                // Set content size
//                response.setContentLength((int) file.length());
//
//                // Open the file and output streams
//                FileInputStream inputStream = new FileInputStream(file);
//                OutputStream out = response.getOutputStream();
//
//                // Copy the contents of the file to the output stream
//                byte[] buf = new byte[DEFAULT_BUFFER_SIZE];
//                int count = 0;
//                while ((count = inputStream.read(buf)) >= 0) {
//                    out.write(buf, 0, count);
//                }
//                inputStream.close();
//                out.close();
//            }
            else {
                // Set content size
                response.setContentLength((int) file.length());

                // Open the file and output streams
                FileInputStream inputStream = new FileInputStream(file);
                OutputStream out = response.getOutputStream();

                // Copy the contents of the file to the output stream
                byte[] buf = new byte[DEFAULT_BUFFER_SIZE];
                int count = 0;
                while ((count = inputStream.read(buf)) >= 0) {
                    out.write(buf, 0, count);
                }
                inputStream.close();
                out.close();

            }
        } else if (ranges.size() == 1) {
            // Return single part of file.
            Range r = ranges.get(0);
            response.setHeader("Content-Range", "bytes " + r.start + "-" + r.end + "/" + r.total);
            response.setHeader("Content-Length", String.valueOf(r.length));
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT); // 206.

            BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file));

            OutputStream output = response.getOutputStream();
            byte[] buf = new byte[DEFAULT_BUFFER_SIZE];
            long count = r.start;
            try {

                // Copy single part range.
                long ret = bis.skip(r.start);
                if (ret != r.start) {
                    log.error("Failed to read range request!");
                    bis.close();
                    output.close();
                    return;
                }

                while (count < r.end) {
                    int bret = bis.read(buf, 0, DEFAULT_BUFFER_SIZE);
                    if (bret != -1) {
                        output.write(buf, 0, bret);
                        count += bret;
                    } else break;
                }

            } catch (Exception e) {
                log.error(e.message);
                e.printStackTrace();
            }

            output.close();
            bis.close();

        }

    }

    private String calculateOriginalChunkName(List<ProjectionChunk> projectionChunks, String finalSequenceString, Integer chunkIndex) {
        for (int i = 0; i < projectionChunks.size(); i++) {
            if (projectionChunks.get(i).sequence == finalSequenceString) {
                return "lf-${chunkIndex - i + 1}.json"
            }
        }
        println "unable to find an offset "
        return "lf-${chunkIndex + 1}.json"
    }

    def trackList() {
        String dataDirectory = getJBrowseDirectoryForSession()
        String absoluteFilePath = dataDirectory + "/trackList.json"
        File file = new File(absoluteFilePath);
        def mimeType = "application/json";
        response.setContentType(mimeType);
        int id

        if (!file.exists()) {
            log.warn("Could not get for name and path: ${absoluteFilePath}");
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            render status: NOT_FOUND
            return;
        }

        // add datasets to the configuration
        JSONObject jsonObject = JSON.parse(file.text) as JSONObject
        Organism currentOrganism = preferenceService.currentOrganismForCurrentUser
//        projectionService.createTranscriptProjection(currentOrganism, jsonObject.getJSONArray(FeatureStringEnum.TRACKS.value),50)

        // this comes from the
//        if (grailsApplication.config.apollo.doProjection) {
//            if (grailsApplication.config.apollo.useMultiSequence) {
//                ProjectionDescription projectionDescription = new ProjectionDescription()
//                projectionDescription.padding = 50
////                projectionDescription.featureNames = ["ALL"]
//                projectionDescription.referenceTracks = ["Official Gene Set v3.2"]
//                // TODO: get the proper name from the UI
//                projectionDescription.projection = "EXON"
//                List<Location> locationList = projectionService.extractExonLocations(currentOrganism, jsonObject.getJSONArray(FeatureStringEnum.TRACKS.value), projectionDescription)
//                projectionService.createMultiSequenceProjection(projectionDescription, locationList)
//            } else {
//                projectionService.createExonLevelProjection(currentOrganism, jsonObject.getJSONArray(FeatureStringEnum.TRACKS.value), 50)
//            }
//        }

        if (currentOrganism != null) {
            jsonObject.put("dataset_id", currentOrganism.id)
        } else {
            id = request.session.getAttribute(FeatureStringEnum.ORGANISM_ID.value);
            jsonObject.put("dataset_id", id);
        }
        List<Organism> list = permissionService.getOrganismsForCurrentUser()
        JSONObject organismObjectContainer = new JSONObject()
        for (organism in list) {
            JSONObject organismObject = new JSONObject()
            organismObject.put("name", organism.commonName)
            String url = "javascript:window.top.location.href = '../annotator/loadLink?"
            url += "organism=" + organism.getId();
            url += "&highlight=0";
            url += "&tracks='";
            organismObject.put("url", url)
            organismObjectContainer.put(organism.id, organismObject)
        }

        if (list.size() == 0) {
            JSONObject organismObject = new JSONObject()
            organismObject.put("name", Organism.findById(id).commonName)
            organismObject.put("url", "#")
            organismObjectContainer.put(id, organismObject)
        }

        jsonObject.put("datasets", organismObjectContainer)

        if (jsonObject.include == null) jsonObject.put("include", new JSONArray())
        jsonObject.include.add("../plugins/WebApollo/json/annot.json")

        response.outputStream << jsonObject.toString()
        response.outputStream.close()
    }

    private static boolean isCacheableFile(String fileName) {
        if (fileName.endsWith(".txt") || fileName.endsWith("txtz")) return true;
        if (fileName.endsWith(".json") || fileName.endsWith("jsonz")) {
            String[] names = fileName.split("\\/");
            String requestName = names[names.length - 1];
            return requestName.startsWith("lf-");
        }

        return false;
    }

    private static String formatLastModifiedDate(File file) {
        DateFormat simpleDateFormat = SimpleDateFormat.getDateInstance();
        return simpleDateFormat.format(new Date(file.lastModified()));
    }

    private static String createHashFromFile(File file) {
        String fileName = file.getName();
        long length = file.length();
        long lastModified = file.lastModified();
        return fileName + "_" + length + "_" + lastModified;
    }

    /**
     * Returns a substring of the given string value from the given begin index to the given end
     * index as a long. If the substring is empty, then -1 will be returned
     *
     * @param value The string value to return a substring as long for.
     * @param beginIndex The begin index of the substring to be returned as long.
     * @param endIndex The end index of the substring to be returned as long.
     * @return A substring of the given string value as long or -1 if substring is empty.
     */
    private static long sublong(String value, int beginIndex, int endIndex) {
        String substring = value.substring(beginIndex, endIndex);
        return (substring.length() > 0) ? Long.parseLong(substring) : -1;
    }
}
