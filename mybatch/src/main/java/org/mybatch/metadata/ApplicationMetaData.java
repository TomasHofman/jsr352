/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012-2013, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mybatch.metadata;

import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.batch.operations.exception.JobStartException;

import org.mybatch.job.BatchArtifactRef;
import org.mybatch.job.BatchArtifacts;
import org.scannotation.AnnotationDB;
import org.scannotation.ClasspathUrlFinder;

import static org.mybatch.util.BatchLogger.LOGGER;

public class ApplicationMetaData {
    private AnnotationDB annotationDB;

    //current build-in default is {"javax", "java", "sun", "com.sun", "javassist"}
    private static String[] ignoredPkgs = {
    };

    private Map<String, String> artifactCatalog = new HashMap<String, String>();

    private BatchArtifacts batchArtifacts;

    private ClassLoader classLoader;

    public ApplicationMetaData(ClassLoader classLoader) throws IOException, JobStartException {
        this.classLoader = classLoader;
        annotationDB = new AnnotationDB();
        annotationDB.addIgnoredPackages(ignoredPkgs);
        URL[] urls = ClasspathUrlFinder.findClassPaths();
//        System.out.println("classpath urls: ");
//        for (URL u : urls) {
//            System.out.println(u);
//        }

        annotationDB.setScanClassAnnotations(true);
        annotationDB.setScanMethodAnnotations(false);
        annotationDB.setScanFieldAnnotations(false);
        annotationDB.setScanParameterAnnotations(false);
        annotationDB.scanArchives(urls);

        identifyArtifacts();
        batchArtifacts = ArchiveXmlLoader.loadBatchXml(classLoader);
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public String getClassNameForRef(String ref) {
        String result = artifactCatalog.get(ref);
        if (result == null && batchArtifacts != null) {
            for (BatchArtifactRef r : batchArtifacts.getRef()) {
                if (r.getId().equals(ref)) {
                    result = r.getClazz();
                    break;
                }
            }
        }
        return result;
    }

    private void identifyArtifacts() {
        Map<String, Set<String>> annotationIndex = annotationDB.getAnnotationIndex();
        Set<String> namedClasses = annotationIndex.get("javax.inject.Named");
        if (namedClasses != null) {
            for (String matchingClass : namedClasses) {
                String refName;
                Class<?> cls;
                try {
                    cls = this.classLoader.loadClass(matchingClass);
                    refName = cls.getAnnotation(javax.inject.Named.class).value();
                } catch (ClassNotFoundException e) {
                    LOGGER.failToIdentifyArtifact(e);
                    continue;
                }
                if (refName.isEmpty()) {
                    refName = cls.getSimpleName();
                }
                artifactCatalog.put(refName, matchingClass);
            }
        }
    }
}
