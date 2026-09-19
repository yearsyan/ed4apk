# Bundled dependencies

AQE packages its runtime libraries in `aqe.jar`. The JRE is not part of the JAR.
Exact direct and transitive versions are recorded in `gradle.lockfile` and in
`META-INF/third-party/dependencies.txt` inside the JAR. License and notice files
present in each upstream JAR are retained in separate artifact-specific folders
under `META-INF/third-party/` to avoid collisions during shading.

Main projects:

| Project | Version | Source / license |
| --- | --- | --- |
| picocli | 4.7.7 | https://github.com/remkop/picocli (Apache-2.0) |
| Jackson core / databind | 2.20.2 | https://github.com/FasterXML/jackson (Apache-2.0; annotations version in lockfile) |
| Google smali, baksmali, dexlib2, util | 3.0.10 | https://github.com/google/smali (BSD and bundled notices) |
| ARSCLib | 1.4.0 | https://github.com/REAndroid/ARSCLib (Apache-2.0) |
| Zipflinger | 9.2.1 | https://android.googlesource.com/platform/tools/base/ (Apache-2.0; pinned for Java 11 compatibility) |
| apksig | 9.4.1 | https://android.googlesource.com/platform/tools/apksig/ (Apache-2.0) |
| R8 / D8 | 9.4.24 | https://r8.googlesource.com/r8/ (BSD and bundled notices) |

These projects also bring runtime dependencies, including Guava, ANTLR,
StringTemplate, JCommander and annotation libraries. Their Maven coordinates
are included in the generated dependency list. The upstream license texts
bundled by smali and R8 include additional component notices.
