# Third-Party Notices

The root MIT license covers original PokeMog code, not a blanket relicensing of dependencies, data, fonts or third-party intellectual property.

| Component                                | License / notice                                                                                                                                                                                                                    |
| ---------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| PvPoke-derived data                      | MIT; preserve [the full bundled license](src/data/PVPoke-LICENSE.txt). Provenance and limitations: [DATA_SOURCES.md](DATA_SOURCES.md). Upstream: https://github.com/pvpoke/pvpoke                                                   |
| Silkscreen font                          | SIL Open Font License 1.1; preserve [the full font notice](public/fonts/Silkscreen-OFL.txt), including copyright and any reserved font names. The Android assets retain a copy. Upstream: https://github.com/googlefonts/silkscreen |
| React / React DOM                        | MIT; https://github.com/facebook/react                                                                                                                                                                                              |
| Vite and build/test dependencies         | Each package retains its own license. Review the resolved lockfile and installed package notices when distributing source/build tools.                                                                                              |
| Google ML Kit text recognition (Android) | Proprietary Google SDK terms, not covered by PokeMog's MIT license. https://developers.google.com/ml-kit/terms                                                                                                                      |
| Android and AndroidX dependencies        | Component-specific notices apply; review the resolved Gradle dependency licenses before distributing the final APK.                                                                                                                 |

This is a source-level notice, not a certified exhaustive license inventory of the final release binary. Final dependency/license inventory remains a release verification task. In particular, **the complete Android app is not fully FOSS** because it includes proprietary ML Kit. Recognition input/output stays on-device, but SDK metrics may be transmitted; see the [privacy notice](public/privacy/index.html).

## Names And Artwork

Pokemon, Pokemon GO, Pokeball and related names/designs are subject to third-party copyright and trademark rights. No such rights are granted by the MIT license or the PvPoke data license. The project's pixel Pokeball rendering is original pixel artwork, but that does not establish freedom from design/trademark claims. Publication requires rights review where appropriate.

PokeMog is an unofficial fan beta. No affiliation, authorization or endorsement by Nintendo, The Pokemon Company, Game Freak, Creatures or Niantic is claimed. A fair-use disclaimer does not guarantee that a use is lawful in every jurisdiction. Optional tips do not change this limitation.
