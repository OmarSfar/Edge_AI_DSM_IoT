CONTIKI_PROJECT = sensor
all: $(CONTIKI_PROJECT)

MODULES_REL += ./resources
MODULES += os/net/app-layer/coap
MAKE_NET = MAKE_NET_IPV6

# For Machine Learning
MODULES_REL += /home/omar/.local/lib/python3.12/site-packages/emlearn
INC += /home/omar/.local/lib/python3.12/site-packages/emlearn
TARGET_LIBFILES += -lm

CONTIKI = ../../..
include $(CONTIKI)/Makefile.include