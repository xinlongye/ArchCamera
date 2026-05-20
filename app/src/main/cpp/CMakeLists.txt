
cmake_minimum_required(VERSION 3.22.1)


project("archcamera")

include_directories(${CMAKE_CURRENT_LIST_DIR}/include)


add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp)

add_library(opencv SHARED IMPORTED)
set_target_properties(
        opencv
        PROPERTIES IMPORTED_LOCATION
        ${CMAKE_CURRENT_LIST_DIR}/libs/${ANDROID_ABI}/libopencv_java4.so
)


target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log
        GLESv3
        opencv)