#include "contiki.h"
#include "net/app-layer/coap/coap-engine.h"
#include "net/app-layer/coap/coap-callback-api.h"
#include "os/dev/leds.h"
#include "os/dev/button-hal.h"
#include "data_trace.h" 
#include <stdio.h>
#include <string.h>
#include "lib/random.h" 
#include "coap-blocking-api.h"
#include "power_predictor.h"
#include "sys/node-id.h"
#include "net/ipv6/uip-ds6.h"

float current_power = 0.0f;
float last_valid_power = 0.0f;
float predicted_power = 0.0f;
int load_state = 0;
int red_state = 0;

// Sliding window for power values
static float window[5] = {0.0f, 0.0f, 0.0f, 0.0f, 0.0f};

// Features' mean and scale to normalize the window's elements
static const float FEATURE_MEAN[5] = { 1.6779f, 1.6774f, 1.6778f, 1.6776f, 1.6771f };
static const float FEATURE_SCALE[5] = { 1.3522f, 1.3512f, 1.3510f, 1.3529f, 1.3524f };

// Data history index
static int data_index = 0;

// CoAP resource declarations
extern coap_resource_t res_power;
extern coap_resource_t res_load;

// ML inference function
float run_ml_inference(float new_val) {
  int i;
  float scaled_window[5];
  
  // Shift sliding window
  for(i = 0; i < 4; i++) {
    window[i] = window[i+1];
  }
  window[4] = new_val;
  
  // Normalize data
  for(i = 0; i < 5; i++) {
    scaled_window[i] = (window[i] - FEATURE_MEAN[i]) / FEATURE_SCALE[i];
  }
  
  // Neural network inference
  float prediction = power_predictor_regress1(scaled_window, 5);
  
  // Return prediction
  return prediction;
}

// Response handler
void client_chunk_handler(coap_callback_request_state_t *callback_state) {

  coap_message_t *response = callback_state->state.response;

  if(response == NULL) {
    printf("[CLIENT] No response by neighbour (Timeout)\n");
  }
  else if(response->code == CHANGED_2_04) {
    printf("[CLIENT] M2M command completed with success (Cod 2.04 Changed)\n");
  } 
  else if(response->code == BAD_REQUEST_4_00) {
    printf("[CLIENT] Failed to send the message (Cod 4.00 Bad Request)\n");
  } 
  else {
    printf("[CLIENT] Unexpected response code: %d\n", response->code);
  }
}

// Toggle load state
void toggle_load(void) {
  load_state = !load_state;
  if(load_state) {
    leds_on(LEDS_GREEN);
    printf("[BUTTON] Load ON\n");
  } else {
    leds_off(LEDS_GREEN);
    printf("[BUTTON] Load OFF\n");
  }
}

PROCESS(dsm_server, "DSM CoAP Server");
AUTOSTART_PROCESSES(&dsm_server);

// Process thread
PROCESS_THREAD(dsm_server, ev, data) {

  static struct etimer timer;
  button_hal_button_t *btn;
  
  PROCESS_BEGIN();
  
  printf("[INIT] ML Pointers: %p, %p\n", eml_error_str, eml_net_activation_function_strs);

  coap_engine_init();
  coap_activate_resource(&res_power, "power");
  coap_activate_resource(&res_load, "load");

  btn = button_hal_get_by_index(0);

  if(btn) {
    printf("[BUTTON] Button initialized\n");
  } else {
    printf("[BUTTON] ERROR: Button not found\n");
  }

  // Set timer for 30 seconds
  etimer_set(&timer, CLOCK_SECOND * 30);

  // Main loop
  while(1) {

    PROCESS_WAIT_EVENT();
    
    float sensor_reading;

    if(etimer_expired(&timer)) {
      // Getting own IP
      uip_ds6_addr_t *global_addr = uip_ds6_get_global(ADDR_PREFERRED);

      if (global_addr != NULL && global_addr->ipaddr.u8[15] == 0x6e) {
        sensor_reading = power_values_node1[data_index];
      } else {
        sensor_reading = power_values_node2[data_index];
      }
      data_index = (data_index + 1) % DATA_COUNT;
      
      float ml_input = 0.0f;

      // Simulate faulty sensor (10% probability)
      if((random_rand() % 100) < 10) { 
          current_power = -999.0f;

          // Forward fill: protect ML window with last valid data
          ml_input = last_valid_power; 
          printf("\n[HARDWARE FAILURE] Broken sensor! Sending -999 to Cloud. Using last valid data (%f) for ML.\n", ml_input);
      } else {
          // Sensor working correctly
          current_power = sensor_reading;
          ml_input = sensor_reading;
          last_valid_power = sensor_reading;
      }

      // Run ML inference on clean data
      predicted_power = run_ml_inference(ml_input);
      
      
      printf("P_Net: %f | ML_Input: %f | Pr: %f\n", current_power, ml_input, predicted_power);

      // Notify observers
      coap_notify_observers(&res_power);

      // Edge defense: skip control if data is inconsistent
      if (current_power < 0) {
          printf("[EDGE CONTROL] Warning: Skipping control action due to inconsistent sensor data\n");
      }
      else if(predicted_power > 4) {
        leds_on(LEDS_RED);
        printf("ALERT! Critical prediction.\n");
        red_state=1;

        // Local action
        // Immediately turn off load
        if(load_state == 1) {
            load_state = 0;
            leds_off(LEDS_GREEN);
            printf("[LOCAL] Turned off load for safety.\n");
        }

        // Alert neighbor to turn off load
        printf("[NETWORK] Sending M2M command to my neighbour...\n");
        
        // M2M CLIENT CODE
        static coap_endpoint_t server_ep;
        static coap_message_t request[1];
        
	      static coap_callback_request_state_t m2m_state;

        char neighbor_endpoint[50];
        
        // M2M routing logic based on IP
        if (global_addr != NULL && global_addr->ipaddr.u8[15] == 0x6e) {
            snprintf(neighbor_endpoint, sizeof(neighbor_endpoint), "coap://[fd00::f6ce:361b:a683:bc9c]:5683");
        } else {
            snprintf(neighbor_endpoint, sizeof(neighbor_endpoint), "coap://[fd00::f6ce:364b:b003:a76e]:5683");
        }

        coap_endpoint_parse(neighbor_endpoint, strlen(neighbor_endpoint), &server_ep);
        coap_init_message(request, COAP_TYPE_NON, COAP_PUT, 0);
        coap_set_header_uri_path(request, "load"); 
        
        const char msg[] = "mode=off";
        coap_set_payload(request, (uint8_t *)msg, sizeof(msg) - 1);
        
        coap_send_request(&m2m_state, &server_ep, request, client_chunk_handler);
        // END M2M CLIENT CODE

      } else {
        leds_off(LEDS_RED);
        red_state=0;
      }

      etimer_reset(&timer);
    }
    
    // BUTTON PRESS HANDLER
    if(ev == button_hal_press_event) {
      if(red_state==0){
        printf("[BUTTON] Pressed, changing load state\n");
        toggle_load();
      }
      else {
        printf("[BUTTON] Unable to change load state\n");
      }
    }
  }

  PROCESS_END();
}