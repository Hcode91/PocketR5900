cmake_minimum_required(VERSION 3.22)
project(pocketloader C)

# Tiny NativeActivity entry point. The real game library is compiled on the
# phone into the app's private storage; this loads it and hands over.
add_library(pocketloader SHARED loader.c)
target_link_libraries(pocketloader android log dl)
