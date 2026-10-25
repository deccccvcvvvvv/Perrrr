cmake_minimum_required(VERSION 3.22.1)
project(csnative C)
set(CMAKE_C_STANDARD 11)
add_compile_options(-Wall -Wextra -Wno-unused-parameter -fvisibility=hidden -O2)

# Original GLFW 3.4 API implementation for Android (loaded by LWJGL via org.lwjgl.glfw.libname and by the app via JNI).
add_library(csglfw SHARED csglfw.c input_queue.c host_callbacks.c)
target_link_libraries(csglfw android EGL log dl)

# In-process JVM launcher (dlopen libjvm.so, JNI_CreateJavaVM, stdio redirection, exit hook).
add_library(csbridge SHARED csbridge.c)
target_link_libraries(csbridge csglfw log dl)
