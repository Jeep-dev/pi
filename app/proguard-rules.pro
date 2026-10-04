# Pi Android relies on platform JSON and Compose; JLatexMath needs the keep rules below.

# JLatexMath (chat formulas) looks up its macro handlers and fonts by name.
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }
-dontwarn org.scilab.forge.jlatexmath.**
