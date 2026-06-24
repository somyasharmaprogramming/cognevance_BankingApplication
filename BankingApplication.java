import jakarta.persistence.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// ==========================================
// 1. APPLICATION ENTRY POINT
// ==========================================
@SpringBootApplication
public class BankingApplication implements CommandLineRunner {
    private static final Logger logger = LoggerFactory.getLogger(BankingApplication.class);

    @Autowired
    private AccountRepository accountRepository;

    public static void main(String[] args) {
        SpringApplication.run(BankingApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        logger.info("⚡ Seeding Enterprise Core Banking Data Engine...");
        accountRepository.save(new Account("ACC-1001", "Alice Smith", "alice@bank.com", new BigDecimal("50000.00")));
        accountRepository.save(new Account("ACC-1002", "Bob Jones", "bob@bank.com", new BigDecimal("25000.00")));
        logger.info("✅ Database Seeding Complete.");
    }
}

// ==========================================
// 2. DOMAIN ENTITIES (Hibernate JPA)
// ==========================================
@Entity
@Table(name = "accounts")
class Account {
    @Id
    private String id;
    @Column(nullable = false)
    private String accountHolderName;
    @Column(unique = true, nullable = false)
    private String email;
    @Column(nullable = false)
    private BigDecimal balance;

    @Version // Optimistic Locking for extreme concurrency tuning
    private Long version;

    public Account() {}
    public Account(String id, String accountHolderName, String email, BigDecimal balance) {
        this.id = id;
        this.accountHolderName = accountHolderName;
        this.email = email;
        this.balance = balance;
    }

    // Getters and Setters
    public String getId() { return id; }
    public String getAccountHolderName() { return accountHolderName; }
    public String getEmail() { return email; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
}

@Entity
@Table(name = "transactions")
class Transaction {
    @Id
    private String id;
    private String sourceAccountId;
    private String destinationAccountId;
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    private TransactionType type;
    private LocalDateTime timestamp;

    public Transaction() {}
    public Transaction(String sourceAccountId, String destinationAccountId, BigDecimal amount, TransactionType type) {
        this.id = UUID.randomUUID().toString();
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.amount = amount;
        this.type = type;
        this.timestamp = LocalDateTime.now();
    }

    // Getters
    public String getId() { return id; }
    public String getSourceAccountId() { return sourceAccountId; }
    public String getDestinationAccountId() { return destinationAccountId; }
    public BigDecimal getAmount() { return amount; }
    public TransactionType getType() { return type; }
    public LocalDateTime getTimestamp() { return timestamp; }
}

enum TransactionType { TRANSFER, DEPOSIT, WITHDRAWAL }

// ==========================================
// 3. REPOSITORY LAYER (Data Services)
// ==========================================
@Repository
interface AccountRepository extends JpaRepository<Account, String> {}

@Repository
interface TransactionRepository extends JpaRepository<Transaction, String> {
    List<Transaction> findBySourceAccountIdOrDestinationAccountId(String sourceId, String destId);
}

// ==========================================
// 4. EXCEPTIONS HANDLING ARCHITECTURE
// ==========================================
@ResponseStatus(HttpStatus.BAD_REQUEST)
class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(String message) { super(message); }
}

@ResponseStatus(HttpStatus.NOT_FOUND)
class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String message) { super(message); }
}

@RestControllerAdvice
class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<String> handleFunds(InsufficientFundsException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("🚨 Financial Policy Error: " + ex.getMessage());
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<String> handleNotFound(AccountNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("🔍 Identity Error: " + ex.getMessage());
    }
}

// ==========================================
// 5. CORE TRANSACTION SERVICE (ACID Layer)
// ==========================================
@Service
@Transactional
class BankingService {
    private static final Logger log = LoggerFactory.getLogger(BankingService.class);

    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;

    public void transferFunds(String sourceId, String destId, BigDecimal amount) {
        log.info("Processing transfer of ${} from {} to {}", amount, sourceId, destId);

        Account source = accountRepository.findById(sourceId)
                .orElseThrow(() -> new AccountNotFoundException("Source account code invalid"));
        Account dest = accountRepository.findById(destId)
                .orElseThrow(() -> new AccountNotFoundException("Destination account code invalid"));

        if (source.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException("Account " + sourceId + " has insufficient credit clearance.");
        }

        // Apply balanced double-entry mutations
        source.setBalance(source.getBalance().subtract(amount));
        dest.setBalance(dest.getBalance().add(amount));

        accountRepository.save(source);
        accountRepository.save(dest);

        // Audit Trail Processing
        Transaction tx = new Transaction(sourceId, destId, amount, TransactionType.TRANSFER);
        transactionRepository.save(tx);
        log.info("Transaction Successfully Settled! ID: {}", tx.getId());
    }

    public List<Transaction> getAccountStatement(String accountId) {
        return transactionRepository.findBySourceAccountIdOrDestinationAccountId(accountId, accountId);
    }
}

// ==========================================
// 6. REST API CONTROLLER (Integration Layer)
// ==========================================
@RestController
@RequestMapping("/api/v1/banking")
class BankingController {

    @Autowired private BankingService bankingService;
    @Autowired private AccountRepository accountRepository;

    @GetMapping("/accounts")
    public List<Account> getAllAccounts() {
        return accountRepository.findAll();
    }

    @PostMapping("/transfer")
    public ResponseEntity<String> executeTransfer(@RequestBody TransferRequest request) {
        bankingService.transferFunds(request.getSourceAccountId(), request.getDestinationAccountId(), request.getAmount());
        return ResponseEntity.ok("🚀 Transaction accepted and cleared by internal settlement engine.");
    }

    @GetMapping("/analytics/{accountId}")
    public List<Transaction> getAnalyticsReport(@PathVariable String accountId) {
        return bankingService.getAccountStatement(accountId);
    }
}

class TransferRequest {
    private String sourceAccountId;
    private String destinationAccountId;
    private BigDecimal amount;

    // Getters & Setters
    public String getSourceAccountId() { return sourceAccountId; }
    public void setSourceAccountId(String sourceAccountId) { this.sourceAccountId = sourceAccountId; }
    public String getDestinationAccountId() { return destinationAccountId; }
    public void setDestinationAccountId(String destinationAccountId) { this.destinationAccountId = destinationAccountId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}