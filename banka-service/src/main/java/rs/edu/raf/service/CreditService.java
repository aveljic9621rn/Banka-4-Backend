package rs.edu.raf.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import rs.edu.raf.model.dto.CreditRequestDto;
import rs.edu.raf.model.dto.DetailedCreditDto;
import rs.edu.raf.model.dto.racun.RacunDTO;
import rs.edu.raf.model.entities.Credit;
import rs.edu.raf.model.entities.CreditRequest;
import rs.edu.raf.model.entities.racun.DevizniRacun;
import rs.edu.raf.model.entities.racun.TekuciRacun;
import rs.edu.raf.model.mapper.CreditRequestMapper;
import rs.edu.raf.repository.CreditRepository;
import rs.edu.raf.repository.CreditRequestRepository;
import rs.edu.raf.service.racun.RacunServis;
import rs.edu.raf.service.racun.impl.RacunServisImpl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class CreditService {

    private final CreditRequestRepository creditRequestRepository;
    private final CreditRequestMapper creditRequestMapper;
    private final CreditRepository creditRepository;
    private final RacunServis racunServis;

    @Autowired
    public CreditService(CreditRequestRepository creditRequestRepository, CreditRequestMapper creditRequestMapper, CreditRepository creditRepository, RacunServis racunServis) {
        this.creditRequestRepository = creditRequestRepository;
        this.creditRequestMapper = creditRequestMapper;
        this.creditRepository = creditRepository;
        this.racunServis = racunServis;
    }

    public String applyForCredit(CreditRequestDto creditRequestDto){

        CreditRequest creditRequest = creditRequestMapper.creditRequestDtoToCreditRequest(creditRequestDto);
        creditRequest.setStatus("not_approved");

        System.out.println(creditRequestRepository.save(creditRequest));

        String message = "Uspesno kreiran zahtev za kredit";
        return message;
    }

    public String approveCreditRequest(Long id){
        Optional<CreditRequest> optionalCreditRequest = creditRequestRepository.findById(id);

        String message;
        if(!optionalCreditRequest.isPresent()){
            message = "Ne postoji takav zahtev za kredit";
            return message;
        }

        CreditRequest creditRequest = optionalCreditRequest.get();
        creditRequest.setStatus("approved");


        //TODO kreirati kredit(Credit, ne CreditRequest)
        Object racunObject = racunServis.nadjiRacunPoBrojuRacuna(creditRequest.getBankAccountNumber());
        String tipRacuna = null;
        String currency = null;
        if(racunObject != null){
            if(racunObject instanceof TekuciRacun){
                TekuciRacun racun = (TekuciRacun)racunObject;
                tipRacuna = "tekuci";
                currency = racun.getCurrency();
            }else{
                DevizniRacun racun = (DevizniRacun)racunObject;
                tipRacuna = "devizni";
                currency = racun.getCurrency();
            }
        }

        createCredit(creditRequest, tipRacuna, currency, creditRequest.getSalary());

        message = "Prihvacen je zahtev za kredit";
        return message;
    }

    public String dennyCreditRequest(Long id){
        Optional<CreditRequest> optionalCreditRequest = creditRequestRepository.findById(id);

        String message;
        if(!optionalCreditRequest.isPresent()){
            message = "Ne postoji takav zahtev za kredit";
            return message;
        }

        CreditRequest creditRequest = optionalCreditRequest.get();
        creditRequest.setStatus("denied");

        message = "Odbijen je zahtev za kredit";
        return message;
    }

    public List<CreditRequestDto> getAllCreditRequests(String status){
       List<CreditRequest> creditRequests = creditRequestRepository.findAllByStatus(status);

       return creditRequests.stream()
               .map(creditRequestMapper::creditRequestToCreditRequestDto)
               .collect(Collectors.toList());
    }


    public List<CreditRequestDto> getAllCreditRequestForUser(Long userId, String status){
        List<CreditRequest> deniedCreditRequests = creditRequestRepository.findAllCreditRequestsForUser(userId, status);
        return deniedCreditRequests.stream()
                .map(creditRequestMapper::creditRequestToCreditRequestDto)
                .collect(Collectors.toList());
    }


    public void createCredit(CreditRequest creditRequest, String tipRacuna, String currency, BigDecimal salary){

        if(tipRacuna == null){
            return;
        }

        Credit credit = new Credit();
        credit.setCreditRequestId(creditRequest.getId());
        credit.setAmount(creditRequest.getAmount());
        credit.setBankAccountNumber(creditRequest.getBankAccountNumber());
        credit.setType(creditRequest.getType());
        credit.setLoanTerm(creditRequest.getLoanTerm());

        LocalDateTime currentDateTime = LocalDateTime.now();
        Long currentDateMilis = currentDateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
        credit.setContractDate(currentDateMilis);

        LocalDate currentDate = LocalDate.now();
        LocalDate loanMaturityDate = currentDate.plusMonths(1).withDayOfMonth(1);
        LocalDateTime loanMaturityDateTime = loanMaturityDate.atStartOfDay();
        Long loanMaturityDateMilis = loanMaturityDateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
        credit.setLoanMaturityDate(loanMaturityDateMilis);

        credit.setLoanTerm(5L);
        credit.setPrepayment(creditRequest.getAmount());
        credit.setRemainingDebt(creditRequest.getAmount());
        credit.setCurrency(currency);

        credit.setInstallmentAmount(salary.multiply(BigDecimal.valueOf(0.2)));



        LocalDate nextInstallmentDate = currentDate.plusMonths(1).withDayOfMonth(1);
        LocalDateTime nextInstallmentDateTime = loanMaturityDate.atStartOfDay();
        Long nextInstallmentDateMilis = nextInstallmentDateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
        credit.setNextInstallmentDate(nextInstallmentDateMilis);

        if(tipRacuna.equalsIgnoreCase("tekuci")){
            credit.setNominalInterestRate(BigDecimal.valueOf(0.08));
            credit.setEffectiveInterestRate(BigDecimal.valueOf(0.09));
        }else{
            credit.setNominalInterestRate(BigDecimal.valueOf(0.05));
            credit.setEffectiveInterestRate(BigDecimal.valueOf(0.06));
        }


        creditRepository.save(credit);
    }

    public DetailedCreditDto getDetailedCredit(Long creditRequestId){
        Credit credit = creditRepository.findCreditByCreditRequestId(creditRequestId);

        DetailedCreditDto detailedCreditDto = creditRequestMapper.creditToDetailedCreditDto(credit);

        return detailedCreditDto;
    }

}
