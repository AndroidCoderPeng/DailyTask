cmake_minimum_required(VERSION 3.16)

project(daily_task)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 添加本地 C++ 源码文件
add_library(${PROJECT_NAME} SHARED
        daily_task.cpp
        watermark.cpp
)

# 包含头文件路径
target_include_directories(${PROJECT_NAME} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# 链接库
target_link_libraries(${PROJECT_NAME} android log jnigraphics)