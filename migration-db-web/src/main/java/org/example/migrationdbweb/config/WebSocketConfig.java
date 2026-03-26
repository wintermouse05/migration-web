package org.example.migrationdbweb.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Điểm cuối (Endpoint) để VueJS kết nối tới. 
        // setAllowedOriginPatterns("*") giúp tránh lỗi CORS khi dev Vue ở cổng khác (ví dụ: localhost:5173)
        registry.addEndpoint("/ws-migration")
                .setAllowedOriginPatterns("*")
                .withSockJS(); // Hỗ trợ fallback nếu trình duyệt không thuần WebSocket
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Tiền tố cho các kênh mà Server sẽ gửi tin nhắn đến (Frontend sẽ subscribe kênh này)
        registry.enableSimpleBroker("/topic");

        // Tiền tố cho các tin nhắn từ Client gửi lên Server (nếu cần)
        registry.setApplicationDestinationPrefixes("/app");
    }
}