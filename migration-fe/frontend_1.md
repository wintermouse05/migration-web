Bước chuyển mình từ backend sang frontend là lúc dự án của bạn thực sự "thành hình". Với nền tảng Java vững chắc của bạn, việc tiếp cận Vue.js (đặc biệt là Vue 3 Composition API) sẽ rất tự nhiên vì nó tổ chức logic rất rõ ràng. 

Khả năng phản ứng (reactivity) của hệ thống `v-model` trong Vue sinh ra là để làm việc với các luồng dữ liệu thời gian thực như WebSocket. Chúng ta sẽ sử dụng các hook vòng đời (lifecycle hooks) như `onMounted` để mở kết nối và `onUnmounted` để dọn dẹp tài nguyên.

Dưới đây là cấu trúc chi tiết cho component Vue của bạn.

### Bước 1: Cài đặt thư viện cần thiết

Giả sử bạn đã tạo dự án bằng Vue CLI hoặc Vite, bạn cần cài đặt thêm các thư viện để gọi API và kết nối WebSocket (hỗ trợ STOMP fallback qua SockJS):

```bash
npm install axios @stomp/stompjs sockjs-client
```

---

### Bước 2: Cấu trúc Component `MigrationDashboard.vue`

Thay vì chia nhỏ ngay lập tức, chúng ta sẽ viết toàn bộ logic vào một component lớn để bạn dễ hình dung luồng dữ liệu từ Form -> REST API -> WebSocket -> UI. Tôi sử dụng cú pháp `<script setup>` (chuẩn hiện đại của Vue 3).

```html
<template>
  <div class="migration-container">
    <h1 class="title">Công cụ Database Migration</h1>

    <div class="config-section">
      <div class="card">
        <h3>Source Database (Nguồn)</h3>
        <div class="form-group">
          <label>Loại DB:</label>
          <select v-model="sourceDb.type">
            <option value="ORACLE">Oracle</option>
            <option value="POSTGRESQL">PostgreSQL</option>
          </select>
        </div>
        <div class="form-group">
          <label>Host:</label>
          <input v-model="sourceDb.host" type="text" placeholder="localhost" />
        </div>
        <div class="form-group">
          <label>Port:</label>
          <input v-model.number="sourceDb.port" type="number" />
        </div>
        <div class="form-group">
          <label>Tên DB / SID:</label>
          <input v-model="sourceDb.databaseName" type="text" />
        </div>
        <div class="form-group">
          <label>Username:</label>
          <input v-model="sourceDb.username" type="text" />
        </div>
        <div class="form-group">
          <label>Password:</label>
          <input v-model="sourceDb.password" type="password" />
        </div>
      </div>

      <div class="card">
        <h3>Target Database (Đích)</h3>
        <div class="form-group">
          <label>Loại DB:</label>
          <select v-model="targetDb.type">
            <option value="POSTGRESQL">PostgreSQL</option>
            <option value="ORACLE">Oracle</option>
          </select>
        </div>
        <div class="form-group">
          <label>Host:</label>
          <input v-model="targetDb.host" type="text" />
        </div>
        <div class="form-group">
          <label>Port:</label>
          <input v-model.number="targetDb.port" type="number" />
        </div>
        <div class="form-group">
          <label>Tên DB:</label>
          <input v-model="targetDb.databaseName" type="text" />
        </div>
        <div class="form-group">
          <label>Username:</label>
          <input v-model="targetDb.username" type="text" />
        </div>
        <div class="form-group">
          <label>Password:</label>
          <input v-model="targetDb.password" type="password" />
        </div>
      </div>
    </div>

    <div class="card options-section">
      <h3>Tùy chọn Migration</h3>
      <div class="options-grid">
        <label>
          <input type="radio" value="ALL" v-model="migrationMode" /> Copy Cấu trúc & Dữ liệu
        </label>
        <label>
          <input type="radio" value="STRUCTURE_ONLY" v-model="migrationMode" /> Chỉ copy Cấu trúc
        </label>
        <label>
          <input type="radio" value="DATA_ONLY" v-model="migrationMode" /> Chỉ copy Dữ liệu
        </label>
        <label>
          <input type="checkbox" v-model="options.truncate" :disabled="migrationMode === 'STRUCTURE_ONLY'" />
          Xóa dữ liệu đích trước (Truncate)
        </label>
        <label>
          <input type="checkbox" v-model="options.copyNewOnly" :disabled="migrationMode === 'STRUCTURE_ONLY'" />
          Chỉ copy dữ liệu mới (Resume)
        </label>
        <div class="limit-input">
          <label>Giới hạn số dòng (0 = Tất cả):</label>
          <input v-model.number="options.limit" type="number" min="0" />
        </div>
      </div>
    </div>

    <div class="action-section">
      <button 
        class="btn-start" 
        @click="startMigration" 
        :disabled="isMigrating"
      >
        {{ isMigrating ? 'ĐANG CHẠY...' : 'BẮT ĐẦU MIGRATION' }}
      </button>
    </div>

    <div class="progress-container" v-if="progress > 0 || isMigrating">
      <div class="progress-bar" :style="{ width: progress + '%' }"></div>
      <span class="progress-text">{{ progress }}%</span>
    </div>

    <div class="log-console">
      <div v-for="(log, index) in logs" :key="index" class="log-line">
        <span class="log-time">[{{ log.time }}]</span> {{ log.message }}
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onUnmounted, nextTick } from 'vue';
import axios from 'axios';
import SockJS from 'sockjs-client/dist/sockjs';
import { Stomp } from '@stomp/stompjs';

// --- TRẠNG THÁI UI (STATE) ---
const isMigrating = ref(false);
const progress = ref(0);
const logs = ref([]);
const stompClient = ref(null);

// --- DỮ LIỆU BIỂU MẪU (REACTIVE) ---
const sourceDb = reactive({
  type: 'ORACLE', host: 'localhost', port: 1521, 
  databaseName: 'ORCL', username: 'system', password: ''
});

const targetDb = reactive({
  type: 'POSTGRESQL', host: '127.0.0.1', port: 5432, 
  databaseName: 'migration_db', username: 'postgres', password: ''
});

const migrationMode = ref('ALL');
const options = reactive({
  truncate: false,
  copyNewOnly: false,
  limit: 0
});

// --- HOOK VÒNG ĐỜI (LIFECYCLE HOOKS) ---

// Kích hoạt khi Component được gắn vào DOM
onMounted(() => {
  connectWebSocket();
});

// Dọn dẹp tài nguyên khi Component bị hủy
onUnmounted(() => {
  if (stompClient.value && stompClient.value.connected) {
    stompClient.value.disconnect();
  }
});

// --- PHƯƠNG THỨC (METHODS) ---

const connectWebSocket = () => {
  // Trỏ tới endpoint WebSocket của Spring Boot
  const socket = new SockJS('http://localhost:8080/ws-migration');
  stompClient.value = Stomp.over(socket);
  
  // Tắt log STOMP mặc định để console trình duyệt đỡ rối
  stompClient.value.debug = () => {}; 

  stompClient.value.connect({}, (frame) => {
    addLog('Đã kết nối với máy chủ WebSocket thành công.');
    
    // Lắng nghe các bản tin broadcast từ Server
    stompClient.value.subscribe('/topic/status', (message) => {
      const payload = JSON.parse(message.body);
      
      // Cập nhật thanh tiến trình
      if (payload.progress !== undefined) {
        progress.value = payload.progress;
      }
      
      // Thêm log mới
      if (payload.message) {
        addLog(payload.message);
      }

      // Xử lý hoàn tất hoặc lỗi
      if (payload.progress === 100 || payload.progress === -1) {
        isMigrating.value = false;
      }
    });
  }, (error) => {
    addLog(`Lỗi kết nối WebSocket: ${error}`);
  });
};

const startMigration = async () => {
  if (isMigrating.value) return;

  isMigrating.value = true;
  progress.value = 0;
  logs.value = []; // Xóa log cũ
  
  // Xây dựng Payload (DTO) khớp với backend
  const payload = {
    source: sourceDb,
    target: targetDb,
    structureOnly: migrationMode.value === 'STRUCTURE_ONLY',
    dataOnly: migrationMode.value === 'DATA_ONLY',
    options: options // Gửi kèm các option phụ
  };

  try {
    addLog('Đang gửi cấu hình tới máy chủ...');
    // Gọi REST API của Spring Boot
    const response = await axios.post('http://localhost:8080/api/migration/start', payload);
    addLog(response.data.message);
  } catch (error) {
    addLog(`Lỗi khi gọi API: ${error.response?.data || error.message}`);
    isMigrating.value = false;
  }
};

const addLog = (message) => {
  const now = new Date();
  const timeString = `${now.getHours()}:${now.getMinutes()}:${now.getSeconds()}`;
  logs.value.push({ time: timeString, message });
  
  // Tự động cuộn xuống cuối log (sử dụng nextTick để đợi DOM cập nhật)
  nextTick(() => {
    const consoleEl = document.querySelector('.log-console');
    if (consoleEl) {
      consoleEl.scrollTop = consoleEl.scrollHeight;
    }
  });
};
</script>

<style scoped>
/* CSS cơ bản giúp form gọn gàng - Bạn có thể thay bằng Tailwind CSS */
.migration-container { max-width: 900px; margin: 0 auto; padding: 20px; font-family: Arial, sans-serif; }
.title { text-align: center; color: #2c3e50; }
.config-section { display: flex; gap: 20px; margin-bottom: 20px; }
.card { flex: 1; border: 1px solid #ddd; padding: 15px; border-radius: 8px; background: #f9f9f9; }
.form-group { margin-bottom: 10px; display: flex; flex-direction: column; }
.form-group label { font-weight: bold; margin-bottom: 5px; font-size: 0.9em; }
.form-group input, .form-group select { padding: 8px; border: 1px solid #ccc; border-radius: 4px; }
.options-section { margin-bottom: 20px; }
.options-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 10px; }
.btn-start { width: 100%; padding: 15px; font-size: 1.2em; font-weight: bold; color: white; background-color: #27ae60; border: none; border-radius: 8px; cursor: pointer; transition: background 0.3s; }
.btn-start:disabled { background-color: #95a5a6; cursor: not-allowed; }
.progress-container { width: 100%; background-color: #e0e0e0; border-radius: 4px; margin: 20px 0; position: relative; height: 25px; overflow: hidden; }
.progress-bar { height: 100%; background-color: #2980b9; transition: width 0.3s ease; }
.progress-text { position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); color: white; font-weight: bold; text-shadow: 1px 1px 2px rgba(0,0,0,0.5); }
.log-console { background: #1e1e1e; color: #a9b7c6; height: 300px; overflow-y: auto; padding: 10px; border-radius: 4px; font-family: monospace; }
.log-time { color: #6a8759; }
</style>
```

### Điểm nổi bật trong kiến trúc này:
1.  **Reactive State (`reactive` & `ref`):** Thay vì quản lý dữ liệu lỏng lẻo, `sourceDb` và `targetDb` được nhóm thành các đối tượng `reactive`. Nhờ `v-model`, bất kỳ ký tự nào bạn gõ trên màn hình đều tự động cập nhật vào đối tượng này mà không cần viết hàm lắng nghe sự kiện (`onChange`).
2.  **Bóc tách Luồng (Decoupling):**
    * **Axios:** Chỉ chịu trách nhiệm kích hoạt (Trigger) quá trình và nhận xác nhận ban đầu.
    * **WebSocket:** Đảm nhận hoàn toàn việc lắng nghe trạng thái nền (Background Status). Nếu bạn F5 lại trang, chỉ cần viết thêm một chút logic gọi API `GET /api/status`, component sẽ kết nối lại WebSocket và lấy đúng % đang chạy mà không làm hỏng tiến trình trên Server.
3.  **Cuộn tự động (`nextTick`):** Giống hệt behavior của ứng dụng Desktop, mỗi khi có log mới, `nextTick` chờ Vue render dòng chữ đó ra HTML rồi mới thực hiện đẩy thanh cuộn xuống cuối cùng.

Khi hệ thống lớn lên, component này có thể hơi dài. Bạn có muốn đi tiếp vào việc chia nhỏ giao diện thành các Component con (như `DatabaseConfigForm.vue` và `MigrationLogger.vue`) và sử dụng cơ chế truyền dữ liệu qua `props` và `emit` để code dễ bảo trì hơn không?