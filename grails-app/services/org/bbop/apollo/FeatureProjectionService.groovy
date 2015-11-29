package org.bbop.apollo

import grails.converters.JSON
import grails.transaction.Transactional
import org.bbop.apollo.gwt.shared.FeatureStringEnum
import org.bbop.apollo.projection.DiscontinuousProjection
import org.bbop.apollo.projection.MultiSequenceProjection
import org.codehaus.groovy.grails.web.json.JSONArray
import org.codehaus.groovy.grails.web.json.JSONObject

@Transactional
class FeatureProjectionService {

    def projectionService
    def bookmarkService

    JSONArray projectTrack(JSONArray inputFeaturesArray, Bookmark bookmark, Boolean reverseProjection = false) {
        MultiSequenceProjection projection = projectionService.getProjection(bookmark)
        return projectTrack(inputFeaturesArray, projection, bookmark.organism, (bookmark as JSON).toString(), reverseProjection)
    }

    JSONArray projectTrack(JSONArray inputFeaturesArray, MultiSequenceProjection projection, Organism currentOrganism, String refererLoc, Boolean reverseProjection = false) {


        println "trying to convert ${inputFeaturesArray as JSON}"
        if (projection) {
            // process location . . .
            projectFeaturesArray(inputFeaturesArray, projection, reverseProjection,0)
            println "converted ${inputFeaturesArray as JSON}"
        } else {
            println "no conversion?? "
        }
        return inputFeaturesArray
    }

    /**
     * Anything in this space is assumed to be visible
     * @param sequence
     * @param referenceTrackName
     * @param inputFeaturesArray
     * @return
     */
////    @Transactional(readOnly = true)
//    JSONArray projectFeatures(Sequence sequence, String referenceTrackName, JSONArray inputFeaturesArray, Boolean reverseProjection) {
////        DiscontinuousProjection projection = (DiscontinuousProjection) getProjection(sequence.organism, referenceTrackName, sequence.name)
//        println "trying to convert ${inputFeaturesArray as JSON}"
//        if (projection) {
//            // process location . . .
//            projectFeaturesArray(inputFeaturesArray, projection, reverseProjection)
//            println "converted ${inputFeaturesArray as JSON}"
//        } else {
//            println "no conversion?? "
//        }
//        return inputFeaturesArray
//    }

    private
    static JSONObject projectFeature(JSONObject inputFeature, MultiSequenceProjection projection, Boolean reverseProjection, Integer offset) {
        if (!inputFeature.has(FeatureStringEnum.LOCATION.value)) return inputFeature


        JSONObject locationObject = inputFeature.getJSONObject(FeatureStringEnum.LOCATION.value)
        println "loaction object ${locationObject as JSON}"
        Integer fmin = locationObject.has(FeatureStringEnum.FMIN.value) ? locationObject.getInt(FeatureStringEnum.FMIN.value) : null
        Integer fmax = locationObject.has(FeatureStringEnum.FMAX.value) ? locationObject.getInt(FeatureStringEnum.FMAX.value) : null
        println "old values ${fmin}-${fmax}"
        if (reverseProjection) {
            // TODO: add reverse offset?
            fmin = fmin ? projection.projectReverseValue(fmin) : null
            fmax = fmax ? projection.projectReverseValue(fmax) : null
        } else {
            fmin = fmin ? projection.projectValue(fmin + offset) : null
            fmax = fmax ? projection.projectValue(fmax + offset) : null
        }
        println "new values ${fmin}-${fmax}"
        if (fmin) {
            locationObject.put(FeatureStringEnum.FMIN.value, fmin)
        }
        if (fmax) {
            locationObject.put(FeatureStringEnum.FMAX.value, fmax)
        }
        return inputFeature
    }

    private JSONArray projectFeaturesArray(JSONArray inputFeaturesArray, MultiSequenceProjection projection, Boolean reverseProjection,Integer offset) {
        for (int i = 0; i < inputFeaturesArray.size(); i++) {
            JSONObject inputFeature = inputFeaturesArray.getJSONObject(i)

            if (inputFeature.containsKey(FeatureStringEnum.SEQUENCE.value)) {
                String sequenceName = inputFeature.getString(FeatureStringEnum.SEQUENCE.value)
                offset = projection.getOffsetForSequence(sequenceName)
                println "offset ${offset} for ${sequenceName}"
            } else {
                println "does not contain a sequence name for reference"
                println "${inputFeature as JSON}"
            }

            projectFeature(inputFeature, projection, reverseProjection,offset)
            if (inputFeature.has(FeatureStringEnum.CHILDREN.value)) {
                JSONArray childFeatures = inputFeature.getJSONArray(FeatureStringEnum.CHILDREN.value)
                projectFeaturesArray(childFeatures, projection, reverseProjection,offset)
            }
        }
        return inputFeaturesArray
    }
}
