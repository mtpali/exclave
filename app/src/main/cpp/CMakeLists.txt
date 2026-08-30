cmake_minimum_required(VERSION 3.22.1)
project(v LANGUAGES CXX)

if(NOT DEFINED MOBILETINA_NATIVE_TOKEN)
    set(MOBILETINA_NATIVE_TOKEN "")
endif()

string(LENGTH "${MOBILETINA_NATIVE_TOKEN}" TOKEN_LENGTH)
if(NOT TOKEN_LENGTH EQUAL 64 OR NOT MOBILETINA_NATIVE_TOKEN MATCHES "^[0-9a-f]+$")
    set(MOBILETINA_NATIVE_TOKEN "0000000000000000000000000000000000000000000000000000000000000000")
endif()

string(REGEX MATCHALL "[0-9a-f][0-9a-f]" TOKEN_BYTES "${MOBILETINA_NATIVE_TOKEN}")
set(TOKEN_INITIALIZER "")
foreach(TOKEN_BYTE IN LISTS TOKEN_BYTES)
    string(APPEND TOKEN_INITIALIZER "0x${TOKEN_BYTE},")
endforeach()

file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/generated")
configure_file(native_token.h.in generated/native_token.h @ONLY)

add_library(v SHARED mobiletina_guard.cpp)
target_include_directories(v PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/generated")
target_compile_features(v PRIVATE cxx_std_17)
target_compile_options(v PRIVATE
    -Os
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
    -fno-exceptions
    -fno-rtti
)
target_link_options(v PRIVATE
    -Wl,--gc-sections
    -Wl,--strip-all
    -Wl,-z,max-page-size=16384
)
