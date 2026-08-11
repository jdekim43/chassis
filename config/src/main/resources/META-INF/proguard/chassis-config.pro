# full mode에서 @Configuration 클래스의 어노테이션이 지워져 resolver가 MissingAnnotation을 던지는 것을 막는다.
-keep,allowshrinking,allowobfuscation @kim.jade.chassis.config.Configuration class *

# serializer(type)가 companion 필드(이름과 무관)를 찾지 못해 "Serializer for class is not found"로 실패하는 것을 막는다.
-if @kim.jade.chassis.config.Configuration class **
-keepclassmembers class <1> { static <1>$* *; }

# serializer(type)가 companion의 serializer()를 찾지 못해 "Serializer for class is not found"로 실패하는 것을 막는다.
-if @kim.jade.chassis.config.Configuration class ** { static **$* *; }
-keepclassmembers class <2>$<3> { kotlinx.serialization.KSerializer serializer(...); }

# 앱의 keep이 ConfigOption에 닿을 때 Clikt와 chassis-cli가 없어 R8이 missing class로 실패하는 것을 막는다.
-dontwarn com.github.ajalt.clikt.**
-dontwarn kim.jade.chassis.cli.GlobalOptions
