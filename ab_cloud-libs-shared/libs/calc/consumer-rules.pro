# native/qalc_jni.cc registers QalcNative's methods BY NAME in JNI_OnLoad (RegisterNatives);
# a renamed or stripped class or method fails the load with no other symptom.
-keep class com.diegonmarcos.cloudlib.calc.QalcNative { *; }
