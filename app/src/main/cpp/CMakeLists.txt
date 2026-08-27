cmake_minimum_required(VERSION 3.22.1)
project(tamaemu LANGUAGES C)

set(CMAKE_C_STANDARD 11)

add_library(tamaemu SHARED
        native-emu.c
        core/cpu.c
        core/mem.c
        core/periph.c
        core/lcd.c
        core/device.c
        core/disasm.c
        core/pn512.c
        core/nfcpeer.c
        core/link.c
        core/savepath.c
        core/state.c
        dlc/dlc.c
        dlc/dlc_names_4u.c)

target_include_directories(tamaemu PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/core
        ${CMAKE_CURRENT_SOURCE_DIR}/dlc)

# Vorlaufzeit des Verbindungsspiels: nur noch der Startwert, einstellbar zur
# Laufzeit (link_set_lead_us). Im Labor sprach alles fuer 20 ms gegen das
# Zittern; am Geraet kamen damit zwar alle Bytes sauber an, der Handschlag
# scheiterte aber - zu viel Vorlauf laesst die Zeitschranken der Firmware
# ablaufen. Deshalb steht die Vorgabe wieder auf dem Wert des Kerns.
target_compile_definitions(tamaemu PRIVATE LINK_LEAD_US=1500)

target_compile_options(tamaemu PRIVATE
        -O3 -fno-strict-aliasing -fvisibility=hidden
        -Wno-unused-parameter -Wno-unused-variable -Wno-unused-but-set-variable)

find_library(log-lib log)
target_link_libraries(tamaemu ${log-lib})
