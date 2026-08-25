#include "contiki.h"
#include "coap-engine.h"
#include <string.h>
#include <stdio.h>

extern float current_power;
extern float predicted_power;
static void res_get_handler(coap_message_t *request, coap_message_t *response, uint8_t *buffer, uint16_t preferred_size, int32_t *offset);

EVENT_RESOURCE(res_power,
         "title=\"Power Sensor\";obs;ct=50",
         res_get_handler,
         NULL,
         NULL,
         NULL,
         NULL);

static void res_get_handler(coap_message_t *request, coap_message_t *response, uint8_t *buffer, uint16_t preferred_size, int32_t *offset) {
  
  // Separate the integer from the decimal part to print the JSON
  int p_int = (int)current_power;
  int p_frac = (int)(current_power * 100.0f) % 100;
  if (p_frac < 0) p_frac = -p_frac;

  int pr_int = (int)predicted_power;
  int pr_frac = (int)(predicted_power * 100.0f) % 100;
  if (pr_frac < 0) pr_frac = -pr_frac;

  int length = snprintf((char *)buffer, preferred_size, "{\"P\":%d.%02d, \"Pr\":%d.%02d}", p_int, p_frac, pr_int, pr_frac);

  // Set content format to JSON
  coap_set_header_content_format(response, APPLICATION_JSON);
  coap_set_header_etag(response, (uint8_t *)&length, 1);
  coap_set_payload(response, buffer, length);
}