[![Release](https://jitpack.io/v/umjammer/vavi-sound-lc3.svg)](https://jitpack.io/#umjammer/vavi-sound-lc3)
[![Java CI](https://github.com/umjammer/vavi-sound-lc3/actions/workflows/maven.yml/badge.svg)](https://github.com/umjammer/vavi-sound-lc3/actions/workflows/maven.yml)
[![CodeQL](https://github.com/umjammer/vavi-sound-lc3/actions/workflows/codeql.yml/badge.svg)](https://github.com/umjammer/vavi-sound-lc3/actions/workflows/codeql-analysis.yml)
![Java](https://img.shields.io/badge/Java-8-b07219)

# vavi-sound-lc3

[Fraunhofer LC3plus Codec](https://www.iis.fraunhofer.de/en/ff/amm/communication/lc3.html) for Java

<img src="https://github.com/umjammer/vavi-image-avif/assets/493908/b1bce591-715c-4840-84e5-d6da0e628d32" width="200" />
<sub><a href="https://www.iis.fraunhofer.de/en/ff/amm/communication/lc3.html">© Fraunhofer IIS</a></sub>

## Install

 * create libLC3plus.dylib ... https://github.com/bluekitchen/libLC3plus
 * maven ... https://jitpack.io/#umjammer/vavi-sound-lc3
 *  jvm option `-Djna.library.path=/dir/to/dylib`

## References

 * https://github.com/bluekitchen/libLC3plus
 * https://github.com/xiaojsoft/lc3codec.js
 * https://github.com/google/liblc3
 * https://kamedo2.hatenablog.jp/entry/2022/06/29/022100

## TODO

 * research difference between `NativeLong` and `PointerByReference` at method argument
   * this time `NativeLong` works well and `PointerByReference` does not
