#include "contiki.h"
#include "coap-engine.h"
#include "os/dev/leds.h"
#include <string.h>
#include <stdio.h>
extern int load_state;
extern int red_state;
static void res_put_handler(coap_message_t *request, coap_message_t *response, uint8_t *buffer, uint16_t preferred_size, int32_t *offset);

RESOURCE(res_load,
         "title=\"Load Actuator: PUT mode=on|off\";rt=\"Control\"",
         NULL,
         NULL,
         res_put_handler,
         NULL);

static void res_put_handler(coap_message_t *request, coap_message_t *response, uint8_t *buffer, uint16_t preferred_size, int32_t *offset) {
  size_t len = 0;
  const char *mode = NULL;

  // Check the state of the mode variable
  if((len = coap_get_post_variable(request, "mode", &mode))) {
    if(strncmp(mode, "on", len) == 0) {
      if (red_state==0){
        leds_on(LEDS_GREEN);
        load_state = 1;
        printf("Actuator: Load ON\n");
      }
    } else if(strncmp(mode, "off", len) == 0) {
      leds_off(LEDS_GREEN);
      load_state = 0;
      printf("Actuator: Load OFF\n");
    }
    coap_set_status_code(response, CHANGED_2_04);
  } else {
    coap_set_status_code(response, BAD_REQUEST_4_00);
  }
}